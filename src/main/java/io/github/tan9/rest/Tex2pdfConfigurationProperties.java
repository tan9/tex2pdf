package io.github.tan9.rest;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@ConfigurationProperties(prefix = "tex2pdf")
@Data
public class Tex2pdfConfigurationProperties {

    private Path workDirectory;

}
