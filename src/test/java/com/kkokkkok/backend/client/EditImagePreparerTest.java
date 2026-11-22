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

    @Test void textCueClearsItsPolygonsAndNotTheRestOfItsBox() throws Exception {
        // A slanted line: the box is the whole lower-left half square, the polygon only a triangle of it.
        Map<String,Object> cue = Map.of("id","synthetic","type","text","coordinateSpace","analysis-normalized",
                "bbox",List.of(0.0,0.0,.5,.5),
                "regions",List.of(Map.of("kind","text","bbox",List.of(0.0,0.0,.5,.5),
                        "polygon",List.of(List.of(0.0,0.0),List.of(.5,0.0),List.of(0.0,.5)))));
        BufferedImage mask = mask(100,100,List.of(cue));
        assertTrue(cleared(mask,10,10));
        assertFalse(cleared(mask,45,45));   // inside the box, outside the polygon
        assertFalse(cleared(mask,70,70));
    }

    @Test void objectCueClearsItsMaskCellsScaledToThePhoto() throws Exception {
        // 4 columns x 2 rows over an 80x40 photo; set cells: row 0 column 3 and row 1 column 0 (one run
        // across the row end) -> counts 3 unset, 2 set, 3 unset.
        Map<String,Object> cue = Map.of("id","synthetic","type","object","coordinateSpace","analysis-normalized",
                "bbox",List.of(0.0,0.0,1.0,1.0),
                "regions",List.of(Map.of("kind","object","bbox",List.of(0.0,0.0,1.0,1.0),
                        "mask",Map.of("size",List.of(2,4),"counts",List.of(3,2,3)))));
        BufferedImage mask = mask(80,40,List.of(cue));
        for(int y=0;y<40;y++) for(int x=0;x<80;x++)
            assertEquals((y<20 && x>=60) || (y>=20 && x<20), cleared(mask,x,y), x+","+y);
    }

    @Test void regionOutsideTheCueBoxOrMalformedLeavesTheBox() throws Exception {
        Map<String,Object> cue = Map.of("id","synthetic","type","object","coordinateSpace","analysis-normalized",
                "bbox",List.of(.25,.25,.5,.5),
                "regions",List.of(
                        // another phrase's detection elsewhere in the photo
                        Map.of("kind","object","bbox",List.of(.6,.6,.9,.9),
                                "mask",Map.of("size",List.of(1,1),"counts",List.of(0,1))),
                        // run lengths that do not add up to the grid
                        Map.of("kind","object","bbox",List.of(.25,.25,.5,.5),
                                "mask",Map.of("size",List.of(2,2),"counts",List.of(1,1))),
                        Map.of("kind","text","bbox",List.of(.25,.25,.5,.5),"polygon",List.of(List.of(.3,.3)))));
        BufferedImage mask = mask(40,40,List.of(cue));
        for(int y=0;y<40;y++) for(int x=0;x<40;x++)
            assertEquals(x>=10 && x<20 && y>=10 && y<20, cleared(mask,x,y), x+","+y);
    }

    @Test void modelMaskGrowsBeyondTheClueShapeOnRealSizedPhotos() throws Exception {
        // 400x400: margin 3px + fade 2px -> the model repaints up to 5px around the 160..240 box.
        BufferedImage mask = mask(400,400,List.of(cue(.4,.4,.6,.6)));
        assertTrue(cleared(mask,200,200));
        assertTrue(cleared(mask,156,200));
        assertTrue(cleared(mask,244,200));
        assertFalse(cleared(mask,245,245));   // the corner is rounded, not squared off
        assertFalse(cleared(mask,152,200));
        assertFalse(cleared(mask,100,100));
    }

    @Test void composeKeepsEveryPixelOutsideTheEditAreaAndUsesTheEditInside() throws Exception {
        int size=400;
        var random=new java.util.Random(7);
        BufferedImage source=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++)
            source.setRGB(x,y,new Color(40+random.nextInt(150),40+random.nextInt(150),40+random.nextInt(150)).getRGB());
        var preparer=new EditImagePreparer();
        var prepared=preparer.prepare(new EditSubmission(UUID.randomUUID(),encode(source,"png"),"image/png",
                List.of(cue(.4,.4,.6,.6)),"",50));

        // The "model": repaints the whole photo 20 levels brighter, at another size, with the clue turned blue.
        int out=300;
        BufferedImage generated=new BufferedImage(out,out,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<out;y++) for(int x=0;x<out;x++) {
            boolean clue=x>=out*.4 && x<out*.6 && y>=out*.4 && y<out*.6;
            Color base=new Color(source.getRGB(x*size/out,y*size/out));
            generated.setRGB(x,y,clue ? new Color(20,20,220).getRGB()
                    : new Color(base.getRed()+20,base.getGreen()+20,base.getBlue()+20).getRGB());
        }
        var edited=preparer.compose(prepared,encode(generated,"png"));
        assertEquals("image/png",edited.contentType());
        BufferedImage result=ImageIO.read(new ByteArrayInputStream(edited.bytes()));
        assertEquals(size,result.getWidth());assertEquals(size,result.getHeight());

        int reach=prepared.grow()+prepared.feather();
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
            boolean outside=x<160-reach-1 || x>=240+reach+1 || y<160-reach-1 || y>=240+reach+1;
            if(outside) assertEquals(source.getRGB(x,y),result.getRGB(x,y),x+","+y);
        }
        // Inside: the edit, with the +20 tone shift taken back out by matching the ring outside the mask.
        Color center=new Color(result.getRGB(200,200));
        assertTrue(Math.abs(center.getRed())<=4 && Math.abs(center.getGreen())<=4 && Math.abs(center.getBlue()-200)<=4,center.toString());
    }

    @Test void cropSendsOnlyTheClueAreaWithContextAndBlendsItBackInPlace() throws Exception {
        int size=1000;
        BufferedImage source=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) source.setRGB(x,y,new Color(60+(x*7+y*3)%120,90,120).getRGB());
        var preparer=new EditImagePreparer();
        var prepared=preparer.prepare(new EditSubmission(UUID.randomUUID(),encode(source,"png"),"image/png",
                List.of(cue(.45,.45,.55,.55)),"",50),EditImagePreparer.Framing.CROP);
        var frame=prepared.frame();
        assertTrue(frame.width<size/2 && frame.height<size/2, frame.toString());
        assertTrue(frame.contains(new java.awt.Rectangle(450,450,100,100)));
        BufferedImage model=ImageIO.read(new ByteArrayInputStream(prepared.imagePng()));
        assertEquals(EditImagePreparer.MODEL_MIN_LONG_SIDE,Math.max(model.getWidth(),model.getHeight()));
        BufferedImage mask=ImageIO.read(new ByteArrayInputStream(prepared.maskPng()));
        assertEquals(model.getWidth(),mask.getWidth());
        assertTrue(cleared(mask,mask.getWidth()/2,mask.getHeight()/2));
        assertFalse(cleared(mask,2,2));

        // The "model" returns the crop, all green, at its own size.
        BufferedImage generated=new BufferedImage(700,700,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<700;y++) for(int x=0;x<700;x++) generated.setRGB(x,y,Color.GREEN.getRGB());
        BufferedImage result=ImageIO.read(new ByteArrayInputStream(preparer.compose(prepared,encode(generated,"png")).bytes()));
        assertEquals(size,result.getWidth());
        assertTrue(new Color(result.getRGB(500,500)).getGreen()>new Color(source.getRGB(500,500)).getGreen());
        int reach=prepared.grow()+prepared.feather()+1;
        for(int y=0;y<size;y++) for(int x=0;x<size;x++)
            if(x<450-reach||x>=550+reach||y<450-reach||y>=550+reach) assertEquals(source.getRGB(x,y),result.getRGB(x,y),x+","+y);
    }

    @Test void largePhotoIsSentToTheEditorAtMostTheModelSize() throws Exception {
        BufferedImage source=new BufferedImage(3000,1500,BufferedImage.TYPE_INT_RGB);
        var prepared=prepare(encode(source,"png"),"image/png",List.of(cue(.1,.1,.2,.2)));
        assertEquals(3000,prepared.width());
        BufferedImage model=ImageIO.read(new ByteArrayInputStream(prepared.imagePng()));
        assertEquals(2048,model.getWidth());assertEquals(1024,model.getHeight());
    }

    @Test void blendWeightIsFullInsideTheMarginAndFadesToZero() {
        assertEquals(1.0,EditComposite.weight(0,9,5));
        assertEquals(1.0,EditComposite.weight(9*3,9,5));
        double mid=EditComposite.weight(11*3,9,5);
        assertTrue(mid>0 && mid<1);
        assertEquals(0.0,EditComposite.weight(14*3,9,5));
        assertEquals(1.0,EditComposite.weight(0,0,0));
        assertEquals(0.0,EditComposite.weight(1,0,0));
    }

    private BufferedImage mask(int width,int height,List<Map<String,Object>> cues) throws Exception {
        var source = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        return ImageIO.read(new ByteArrayInputStream(prepare(encode(source,"png"),"image/png",cues).maskPng()));
    }
    private boolean cleared(BufferedImage mask,int x,int y) { return (mask.getRGB(x,y)>>>24)==0; }
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
