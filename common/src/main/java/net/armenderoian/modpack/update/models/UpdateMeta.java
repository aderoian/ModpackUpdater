package net.armenderoian.modpack.update.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Builder
@ToString
@AllArgsConstructor
public class UpdateMeta implements Serializable {
    private String name;
    private String version;
    private String description;
    private String releaseDate;
}