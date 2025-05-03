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
public class UpdateMeta implements Serializable, Comparable<UpdateMeta> {
    private String name;
    private String version;
    private String description;
    private String releaseDate;

    public int compareTo(UpdateMeta other) {
        var thisVersion = parseVersion(this.version);
        var otherVersion = parseVersion(other.version);

        for (int i = 0; i < Math.min(thisVersion.length, otherVersion.length); i++) {
            if (thisVersion[i] != otherVersion[i]) {
                return Integer.compare(thisVersion[i], otherVersion[i]);
            }
        }

        return Integer.compare(thisVersion.length, otherVersion.length);
    }

    public static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int[] parsedVersion = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            parsedVersion[i] = Integer.parseInt(parts[i]);
        }
        return parsedVersion;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return version.equals(((UpdateMeta) obj).version);
    }
}