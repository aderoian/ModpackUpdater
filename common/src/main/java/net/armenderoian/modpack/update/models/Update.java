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
public class Update implements Serializable {

    private UpdateMeta meta;
    private UpdateEntry[] entries;

}