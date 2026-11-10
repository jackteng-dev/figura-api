package dev.jackteng.figura.expection;

import dev.jackteng.figura.pojo.Result;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.ObjectError;


import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<Result> handleParameter(Exception e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Result.error("參數格式不正確"));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Result> handleStatus(org.springframework.web.server.ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Result.error(e.getReason()));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public org.springframework.http.ResponseEntity<Result> handleImageSize(Exception e){return org.springframework.http.ResponseEntity.status(413).body(Result.error("圖片最大5MB，請縮小檔案"));}
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,org.springframework.validation.BindException.class})
    public org.springframework.http.ResponseEntity<Result> handleMultipart(Exception e){return org.springframework.http.ResponseEntity.badRequest().body(Result.error("圖片欄位或檔案不正確"));}
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<Result> handleException(Exception e){

        return org.springframework.http.ResponseEntity.status(500).body(Result.error("操作失敗，請稍後再試"));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<Result> handleInvalidJson(Exception e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Result.error("資料格式或欄位不正確"));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public org.springframework.http.ResponseEntity<Result> handleMethod(Exception e) {
        return org.springframework.http.ResponseEntity.status(405).body(Result.error("不支援此操作"));
    }
    // 处理验证异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<Result> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult result = e.getBindingResult();
        String errorMessage = result.getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return org.springframework.http.ResponseEntity.badRequest().body(Result.error(errorMessage));
    }
}
