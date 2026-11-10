package dev.jackteng.figura.controller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Legacy avatar and arbitrary local-file endpoints are deliberately retired. */
@RestController public class FileUploadController {
 @RequestMapping(value={"/upload","/upload/userAvatar","/upload/updateAvatar"},method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PATCH})
 public void retired(){throw new ResponseStatusException(HttpStatus.GONE,"舊檔案入口已停用，商品圖片請使用商品管理");}
}
