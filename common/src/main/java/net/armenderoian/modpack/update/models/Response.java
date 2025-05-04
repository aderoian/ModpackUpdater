package net.armenderoian.modpack.update.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

@Getter
@AllArgsConstructor
public class Response<T> implements Serializable {
    private final int code;
    @Nullable
    private final String message;
    @Nullable
    private final T data;

    @Override
    public String toString() {
        return super.toString();
    }
}