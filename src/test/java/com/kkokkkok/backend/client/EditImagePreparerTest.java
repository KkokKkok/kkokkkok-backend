package com.kkokkkok.backend.client;

import com.kkokkkok.backend.domain.EditSubmission;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditImagePreparerTest {
    @Test void maskFollowsKnownImageLandmarksInBothAspectRatios() throws Exception {
        for (int[] size : List.of(new int[]{80, 40}, new int[]{40, 80})) {
            BufferedImage source = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            for (int y=0;y<size[1];y++) for(int x=0;x<size[0];x++) {
                boolean landmark = x>=size[0]/4 && x<size[0]/2 && y>=size[1]/4 && y<3*size[1]/4;
                source.setRGB(x,y,landmark ? Color.BLUE.getRGB() : Color.WHITE.getRGB());
            }
            var prepared = prepare(encode(source,"png"),"image/png",List.of(cue(.25,.25,.5,.75)));
            BufferedImage mask = ImageIO.read(new ByteArrayInputStream(prepared.maskPng()));
            for(int y=0;y<size[1];y++) for(int x=0;x<size[0];x++)
                assertEquals(source.getRGB(x,y)==Color.BLUE.getRGB(), (mask.getRGB(x,y)>>>24)==0);
        }
    }

    @Test void rotationUsesUprightDimensionsAndMultipleBoxesOnlyClearTheirUnion() throws Exception {
        BufferedImage source = new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<40;y++) for(int x=0;x<80;x++) source.setRGB(x,y,x<40 ? Color.BLUE.getRGB() : Color.RED.getRGB());
        byte[] jpeg = encode(source,"jpeg");
        // Synthetic EXIF orientation 6: a 90-degree clockwise image. No actual metadata is used.
        byte[] exif = { 'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0 };
        var bytes = new ByteArrayOutputStream();
        bytes.write(jpeg,0,2); bytes.write(new byte[]{(byte)255,(byte)225,0,(byte)(exif.length+2)});bytes.write(exif);bytes.write(jpeg,2,jpeg.length-2);
        var prepared = prepare(bytes.toByteArray(),"image/jpeg",List.of(cue(0,0,1,.5),cue(.5,.75,1,1)));
        assertEquals(40,prepared.width());assertEquals(80,prepared.height());
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(prepared.imagePng()));
        assertTrue(new Color(image.getRGB(20,20)).getBlue()>200);
        assertTrue(new Color(image.getRGB(20,60)).getRed()>200);
        BufferedImage mask=ImageIO.read(new ByteArrayInputStream(prepared.maskPng()));
        for(int y=0;y<80;y++) for(int x=0;x<40;x++)
            assertEquals(y<40 || (x>=20 && y>=60), (mask.getRGB(x,y)>>>24)==0);
    }
    private PreparedEditImage prepare(byte[] bytes,String type,List<Map<String,Object>> cues) {
        return new EditImagePreparer().prepare(new EditSubmission(UUID.randomUUID(),bytes,type,cues,"",50));
    }
    private Map<String,Object> cue(double l,double t,double r,double b) {
        return Map.of("id","synthetic","type","text","coordinateSpace","analysis-normalized","bbox",List.of(l,t,r,b));
    }
    private byte[] encode(BufferedImage image,String format) throws Exception {
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,bytes));return bytes.toByteArray();
    }
}
