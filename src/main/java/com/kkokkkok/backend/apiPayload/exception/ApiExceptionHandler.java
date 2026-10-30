package com.kkokkkok.backend.apiPayload.exception;

import com.kkokkkok.backend.apiPayload.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> api(ApiFailure e) { return error(e.status, e.code, e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size() { return error(413,"PHOTO_TOO_LARGE","사진은 10MB 이하여야 합니다."); }
    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> input(Exception e) { return error(400,"INVALID_REQUEST","필수 입력과 UUID 형식을 확인해 주세요."); }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<?> concurrent() { return error(409,"CONCURRENT_UPDATE","작업 상태가 갱신되었습니다. 같은 요청을 다시 시도해 주세요."); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType() { return error(415,"UNSUPPORTED_MEDIA_TYPE","요청 형식이 API와 맞지 않습니다."); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method() { return error(405,"METHOD_NOT_ALLOWED","지원하지 않는 HTTP 메서드입니다."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) { return error(500,"INTERNAL_ERROR","요청 처리 중 오류가 발생했습니다."); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code,message));
    }
}
