package net.armenderoian.modpack.update.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@ToString
@AllArgsConstructor
public class UpdateEntry {

    private Type type;
    private String id;
    private String name;
    private String version;
    private String downloadUrl;
    private String fileName;

    public String getFormattedFileName() {
        return formatFileName(fileName, id, name, version);
    }

    public static String formatFileName(String fileName, String id, String name, String version) {
        return fileName
                .replace("%i", id)
                .replace("%n", name)
                .replace("%v", version);
    }

    public enum Type {
        ADDED,
        CHANGED,
        REMOVED
    }
}
