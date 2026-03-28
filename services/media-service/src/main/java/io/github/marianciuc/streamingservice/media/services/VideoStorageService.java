/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: VideoStorageService.java
 *
 */

package io.github.marianciuc.streamingservice.media.services;

import io.github.marianciuc.streamingservice.media.dto.ResolutionDto;
import io.minio.errors.MinioException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The VideoStorageService interface provides a contract for managing the storage and retrieval of video files,
 * supporting operations such as uploading video files and chunks, deleting videos, and assembling complete video streams.
 */
public interface VideoStorageService {

    String CONTENT_PATH = "videos/";
    String PATH_CHUNK_TEMPLATE = CONTENT_PATH + "%s/%dp/segment%d.ts";
    String PATH_RESOLUTION_PLAYLIST_TEMPLATE = CONTENT_PATH + "%s/%dp/playlist.m3u8";
    String PATH_MASTER_PLAYLIST_TEMPLATE = CONTENT_PATH + "%s/master.m3u8";
    String PATH_SOURCE_VIDEO_TEMPLATE = CONTENT_PATH + "%s/source.mp4";


    /**
     * Uploads a file to the storage with the specified object name, input stream, size, and content type.
     *
     * @param objectName the name of the object under which the file is to be stored
     * @param inputStream the input stream from which the file's data is to be read
     * @param size the size of the file to be uploaded, in bytes
     * @param contentType the MIME type of the file being uploaded
     * @throws MinioException if an error occurs while interacting with the Minio server
     * @throws IOException if an I/O error occurs
     * @throws NoSuchAlgorithmException if the algorithm for checksum computation is not available
     * @throws InvalidKeyException if the provided key is invalid
     */
    void uploadFile(String objectName, InputStream inputStream, long size, String contentType) throws MinioException, IOException, NoSuchAlgorithmException, InvalidKeyException;


    /**
     * Uploads a chunk of a video file to the storage system.
     *
     * @param chunk the video chunk to be uploaded
     * @param chunkNumber the sequence number of this chunk in the overall video file
     * @param fileId the unique identifier of the video file to which this chunk belongs
     */
    void uploadVideoChunk(MultipartFile chunk, Integer chunkNumber, UUID fileId);

    /**
     * Uploads a source file for a video under the canonical storage path used by transcoding.
     *
     * @param videoId the unique identifier of the video
     * @param inputStream the source video stream
     * @param size the size of the source video in bytes
     * @param contentType the MIME type of the source video
     */
    void uploadSourceVideo(UUID videoId, InputStream inputStream, long size, String contentType);

    /**
     * Uploads a single HLS transport stream segment and returns its storage path.
     *
     * @param segmentBytes the encoded segment bytes
     * @param videoId the unique identifier of the video
     * @param resolution the target resolution
     * @param chunkNumber the sequential segment number
     * @param contentType the MIME type of the segment
     * @return the object path in storage
     */
    String uploadVideoSegment(ByteArrayOutputStream segmentBytes, UUID videoId, ResolutionDto resolution, int chunkNumber, String contentType);

    /**
     * Deletes a video file from the storage system.
     *
     * @param fileId the unique identifier of the video file to be deleted
     */
    void deleteVideo(UUID fileId);


    /**
     * Assembles a complete video stream from previously uploaded video chunks.
     *
     * @param fileId the unique identifier of the video file to be assembled
     * @return an InputStream for reading the assembled video
     */
    InputStream assembleVideo(UUID fileId);

    /**
     * Retrieves an input stream for the specified file object from the storage.
     *
     * @param objectName the name of the file object for which an input stream is to be retrieved
     * @return an InputStream for reading the specified file
     */
    InputStream getFileInputStream(String objectName);
}
