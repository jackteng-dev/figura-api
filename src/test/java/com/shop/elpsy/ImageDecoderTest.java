package com.shop.elpsy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.shop.elpsy.service.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
class ImageDecoderTest {
 private byte[] image(String format,int width,int height) throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,out);return out.toByteArray();}
 @Test void pngReencodingStripsTrailingPayloadAndUsesBytesNotFilename() throws Exception{var out=new ByteArrayOutputStream();out.write(image("png",2,3));out.write("<script>payload</script>".getBytes());var source=out.toByteArray();var decoded=new ImageDecoder().decode(new MockMultipartFile("file","../../evil.html","image/png",source));assertEquals(2,decoded.width());assertEquals(3,decoded.height());assertFalse(new String(decoded.bytes(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("script"));assertEquals(ImageDecoder.sha(source),decoded.sourceSha());assertEquals(ImageDecoder.sha(decoded.bytes()),decoded.sha());}
 @Test void jpegAcceptedAndInvalidOrOversizedDataRejected() throws Exception{var decoder=new ImageDecoder();assertEquals("image/jpeg",decoder.decode(new MockMultipartFile("file","no-extension","image/jpeg",image("jpeg",5,4))).type());for(var f:new MockMultipartFile[]{new MockMultipartFile("file","f","image/png",new byte[5242881]),new MockMultipartFile("file","f","image/png",new byte[]{1,2,3}),new MockMultipartFile("file","f","image/gif",image("gif",1,1))})assertEquals(400,assertThrows(ResponseStatusException.class,()->decoder.decode(f)).getStatusCode().value());}
 @Test void dimensionGuardChecksMetadataBeforeDecodingFullPixels() throws Exception{var f=new MockMultipartFile("file","huge.png","image/png",image("png",4001,4000));assertEquals(400,assertThrows(ResponseStatusException.class,()->new ImageDecoder().decode(f)).getStatusCode().value());}
 @Test void defaultDisabledMutationsRejectBeforeStorageOrDatabase(){var s=new ProductImageService(null,null,null,null,null,null,new org.springframework.jdbc.datasource.DataSourceTransactionManager(),false);assertEquals(404,assertThrows(ResponseStatusException.class,()->s.upload(null,null,null,null)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->s.remove(null,null,null)).getStatusCode().value());}
}
