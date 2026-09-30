package com.kean.service;

import com.kean.vo.FileVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface StorageService {

    FileVO upload(String scene, MultipartFile file);

    boolean exists(String objectKey);

    InputStream open(String objectKey);

    String contentType(String objectKey);
}
