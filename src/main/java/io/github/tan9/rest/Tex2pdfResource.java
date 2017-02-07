package io.github.tan9.rest;

import java.io.File;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;

import lombok.extern.slf4j.Slf4j;

@RestController
@EnableConfigurationProperties(Tex2pdfConfigurationProperties.class)
@Slf4j
public class Tex2pdfResource {

    @Autowired
    private Tex2pdfConfigurationProperties properties;

    @PostMapping("/tex2pdf")
    public
    @ResponseBody
    ResponseEntity<?> tex2pdf(@RequestBody @Validated TexRequest request) {
        try {
            String texContent = request.getTexContent();

            Path tempDirectory = Files.createTempDirectory(properties.getWorkDirectory(), "tex2pdf");
            try {
                Path tempFile = Paths.get(tempDirectory.toString(), "temp.tex");
                Files.createFile(tempFile);
                Files.write(tempFile, texContent.getBytes());

                DockerClient dockerClient = DockerClientBuilder.getInstance().build();
                List<Container> containers = dockerClient.listContainersCmd().exec().stream().filter(container ->
                        "listx/texlive:2016-fonts".equals(container
                                .getImage())).collect(Collectors.toList());

                if (containers.size() != 1) {
                    String message = "Expected exact one texlive container, but found: " + containers;
                    throw new IllegalStateException(message);
                }

                ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containers.iterator().next().getId())
                        .withAttachStdout(true).withCmd("sh", "-c", "cd '" + tempDirectory
                                + "' && xelatex temp.tex").exec();

                dockerClient.execStartCmd(execCreateCmdResponse.getId()).exec(
                        new ExecStartResultCallback(System.out, System.err)).awaitCompletion(1, TimeUnit.MINUTES);

                byte[] pdfBytes = Files.readAllBytes(tempDirectory.resolve("temp.pdf"));
                String encodedPdfContent = Base64.getEncoder().encodeToString(pdfBytes);

                PdfResponse response = new PdfResponse();
                response.setEncodedPdfContent(encodedPdfContent);

                return ResponseEntity.ok(response);

            } finally {
                if (tempDirectory != null) {
                    Files.walk(tempDirectory, FileVisitOption.FOLLOW_LINKS)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }

        } catch (Exception ex) {
            log.error("Failed to process request.", ex);
            return ResponseEntity.unprocessableEntity().body(ex.getMessage());
        }
    }
}
