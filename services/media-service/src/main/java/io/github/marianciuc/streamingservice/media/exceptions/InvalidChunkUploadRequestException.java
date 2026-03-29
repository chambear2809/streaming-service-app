package io.github.marianciuc.streamingservice.media.exceptions;

public class InvalidChunkUploadRequestException extends RuntimeException {

    public InvalidChunkUploadRequestException(String message) {
        super(message);
    }
}
