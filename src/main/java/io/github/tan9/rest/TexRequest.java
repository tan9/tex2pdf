package io.github.tan9.rest;

import javax.validation.constraints.NotNull;

import lombok.Data;

@Data
public class TexRequest {

    @NotNull
    private String texContent;

}
