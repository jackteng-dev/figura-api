package dev.jackteng.figura.service;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.HexFormat;
@Component public class ImageDecoder {
 public record Decoded(byte[] bytes,String sourceSha,String sha,String type,int width,int height){}
 public static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
 private ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"圖片需為有效 PNG／JPEG，最大5MB、每邊4096且不超過1600萬像素");}
 public Decoded decode(MultipartFile file){
  if(file==null||file.isEmpty()||file.getSize()>5242880)throw invalid();
  try{
   byte[] source=file.getBytes();if(source.length>5242880)throw invalid();
   try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(source))){
    var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw invalid();var reader=readers.next();
    try{reader.setInput(stream);String format=reader.getFormatName().toLowerCase();if(!format.equals("png")&&!format.equals("jpeg"))throw invalid();String type="image/"+format;
     if(!type.equals(file.getContentType()))throw invalid();int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>4096||h>4096||(long)w*h>16000000)throw invalid();
     var input=reader.read(0);if(input==null)throw invalid();var clean=new BufferedImage(w,h,format.equals("png")?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);var g=clean.createGraphics();try{g.drawImage(input,0,0,null);}finally{g.dispose();}
     var out=new ByteArrayOutputStream();if(!ImageIO.write(clean,format,out))throw invalid();byte[] bytes=out.toByteArray();if(bytes.length>5242880)throw invalid();return new Decoded(bytes,sha(source),sha(bytes),type,w,h);
    }finally{reader.dispose();}
   }
  }catch(IOException|RuntimeException e){if(e instanceof ResponseStatusException r)throw r;throw invalid();}
 }
}
