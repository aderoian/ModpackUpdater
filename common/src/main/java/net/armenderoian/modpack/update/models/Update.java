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
public class Update implements Serializable, Comparable<Update> {

    private UpdateMeta meta;
    private UpdateEntry[] entries;

    public String toChangelog() {
        StringBuilder changelog = new StringBuilder();

        for (UpdateEntry entry : entries) {
            if (entry.getType() == UpdateEntry.Type.ADDED)
                changelog.append("Added: ").append(entry.getName()).append(" - v").append(entry.getVersion()).append("\n");
            else if (entry.getType() == UpdateEntry.Type.REMOVED)
                changelog.append("Removed: ").append(entry.getName()).append("\n");
            else
                changelog.append("Updated: ").append(entry.getName()).append(" -> v").append(entry.getVersion()).append("\n");
        }

        return changelog.toString();
    }

    public int compareTo(Update other) {
        return this.meta.compareTo(other.meta);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return meta.equals(((Update) obj).meta);
    }
}