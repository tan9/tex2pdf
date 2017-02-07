package io.github.tan9.rest;

import java.io.File;
import java.io.IOException;
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

    private static final String TEMP_TEX_FILENAME = "temp.tex";
    private static final String TEMP_PDF_FILENAME = "temp.pdf";

    @Autowired
    private Tex2pdfConfigurationProperties properties;

    @PostMapping("/tex2pdf")
    public
    @ResponseBody
    ResponseEntity<?> tex2pdf(@RequestBody @Validated TexRequest request) {
        try {
            Path tempDirectory = Files.createTempDirectory(properties.getWorkDirectory(), "tex2pdf");
            try {
                writeTempTexFile(tempDirectory, TEMP_TEX_FILENAME, request.getTexContent());
                performTex2pdf(tempDirectory, TEMP_TEX_FILENAME);
                return ResponseEntity.ok(createPdfResponse(tempDirectory, TEMP_PDF_FILENAME));

            } finally {
                Files.walk(tempDirectory, FileVisitOption.FOLLOW_LINKS)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }

        } catch (Exception ex) {
            log.error("Failed to process request.", ex);
            return ResponseEntity.unprocessableEntity().body(ex.getMessage());
        }
    }

    private void writeTempTexFile(Path tempDirectory, String texFilename, String texContent) throws IOException {
        Path tempFile = Paths.get(tempDirectory.toString(), texFilename);
        Files.createFile(tempFile);
        Files.write(tempFile, texContent.getBytes());
    }

    private void performTex2pdf(Path tempDirectory, String texFilename) throws InterruptedException {
        DockerClient dockerClient = getDockerClient();
        Container container = getTexLiveContainer(dockerClient);

        String command = "cd '" + tempDirectory + "' && " + properties.getTexCommand() + " " + texFilename;
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient
                .execCreateCmd(container.getId())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("sh", "-c", command).exec();

        dockerClient.execStartCmd(execCreateCmdResponse.getId())
                .exec(new ExecStartResultCallback(System.out, System.err))
                .awaitCompletion(1, TimeUnit.MINUTES);
    }

    private DockerClient getDockerClient() {
        return DockerClientBuilder.getInstance().build();
    }

    private Container getTexLiveContainer(DockerClient dockerClient) {
        List<Container> containers = dockerClient.listContainersCmd().exec().stream()
                .filter(container -> properties.getTexliveImage().equals(container.getImage()))
                .collect(Collectors.toList());

        if (containers.size() != 1) {
            String message = "Expected exact one TeX Live container of image: " + properties.getTexliveImage() + ". " +
                    "But found: " + containers;
            throw new IllegalStateException(message);
        }
        return containers.iterator().next();
    }

    private PdfResponse createPdfResponse(Path tempDirectory, String pdfFilename) throws IOException {
        byte[] pdfBytes = Files.readAllBytes(tempDirectory.resolve(pdfFilename));
        String encodedPdfContent = Base64.getEncoder().encodeToString(pdfBytes);

        PdfResponse response = new PdfResponse();
        response.setEncodedPdfContent(encodedPdfContent);
        return response;
    }
}
