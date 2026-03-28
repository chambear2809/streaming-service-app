package io.github.marianciuc.streamingservice.media.exceptions;

public class RtspIngestException extends VideoStorageException {

    public RtspIngestException(String message, Throwable cause) {
        super(message, cause);
    }

    public RtspIngestException(String message) {
        super(message);
    }
}
