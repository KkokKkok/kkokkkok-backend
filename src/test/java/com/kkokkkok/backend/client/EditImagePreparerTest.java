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

    @Test void verifiedTextLineOutsideTheCueBoxIsTakenButAnUnverifiedOneIsNot() throws Exception {
        // The cue box holds one name; the same sign's other name (verified by OCR) and a stray line lie elsewhere.
        Map<String,Object> cue = Map.of("id","synthetic","type","text","coordinateSpace","analysis-normalized",
                "bbox",List.of(0.0,0.0,.25,.25),
                "regions",List.of(
                        Map.of("kind","text","verified",true,"bbox",List.of(0.0,0.0,.25,.25),
                                "polygon",List.of(List.of(0.0,0.0),List.of(.25,0.0),List.of(.25,.25),List.of(0.0,.25))),
                        Map.of("kind","text","verified",true,"bbox",List.of(.5,0.0,.75,.25),
                                "polygon",List.of(List.of(.5,0.0),List.of(.75,0.0),List.of(.75,.25),List.of(.5,.25))),
                        Map.of("kind","text","verified",false,"bbox",List.of(.5,.5,.75,.75),
                                "polygon",List.of(List.of(.5,.5),List.of(.75,.5),List.of(.75,.75),List.of(.5,.75)))));
        BufferedImage mask = mask(40,40,List.of(cue));
        assertTrue(cleared(mask,5,5));
        assertTrue(cleared(mask,25,5));
        assertFalse(cleared(mask,25,25));
    }

    @Test void objectRegionReachingPastTheCueBoxIsTakenWhenItCoversTheBox() throws Exception {
        // A sign board (cue box, top half) whose segmentation also has the post below; another object elsewhere.
        Map<String,Object> cue = Map.of("id","synthetic","type","object","coordinateSpace","analysis-normalized",
                "bbox",List.of(.25,0.0,.5,.5),
                "regions",List.of(
                        Map.of("kind","object","bbox",List.of(.25,0.0,.5,1.0),
                                "mask",Map.of("size",List.of(1,4),"counts",List.of(1,1,2))),
                        Map.of("kind","object","bbox",List.of(.4,.25,1.0,1.0),
                                "mask",Map.of("size",List.of(1,1),"counts",List.of(0,1)))));
        BufferedImage mask = mask(40,40,List.of(cue));
        for(int y=0;y<40;y++) for(int x=0;x<40;x++)
            assertEquals(x>=10 && x<20, cleared(mask,x,y), x+","+y);
    }

    @Test void linesTheEditorLeftReadableAreSentAgainInCloseGroups() throws Exception {
        int width=1000, height=600;
        BufferedImage source=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) source.setRGB(x,y,new Color(200,190,60).getRGB());
        // Three "lines" of dark stripes: two close together on the left, one far right.
        int[][] lines={{100,100},{100,140},{800,450}};
        for(int[] line:lines) for(int y=line[1];y<line[1]+24;y++) for(int x=line[0];x<line[0]+120;x++)
            if((x/6)%2==0) source.setRGB(x,y,Color.BLACK.getRGB());
        var cues=new java.util.ArrayList<Map<String,Object>>();
        for(int i=0;i<lines.length;i++) {
            double l=lines[i][0]/(double)width, t=lines[i][1]/(double)height, r=(lines[i][0]+120)/(double)width, b=(lines[i][1]+24)/(double)height;
            cues.add(Map.of("id","clue-"+i,"type","text","coordinateSpace","analysis-normalized","bbox",List.of(l,t,r,b),
                    "regions",List.of(Map.of("kind","text","bbox",List.of(l,t,r,b),
                            "polygon",List.of(List.of(l,t),List.of(r,t),List.of(r,b),List.of(l,b))))));
        }
        var preparer=new EditImagePreparer();
        var prepared=preparer.prepare(new EditSubmission(UUID.randomUUID(),encode(source,"png"),"image/png",cues,"",50),
                EditImagePreparer.Framing.CROP);
        assertEquals(3,prepared.textParts().size());

        // First edit erased only the far line: the two close lines come back as one close crop.
        BufferedImage firstEdit=copy(source);
        fill(firstEdit,800,450,120,24,new Color(200,190,60));
        var focus=preparer.focusRequests(prepared,firstEdit);
        assertEquals(1,focus.size());
        assertEquals(2,focus.get(0).parts().size());
        assertEquals(List.of("clue-0","clue-1"),focus.get(0).cues().stream().map(c->c.get("id")).toList());
        BufferedImage crop=ImageIO.read(new ByteArrayInputStream(focus.get(0).imagePng()));
        BufferedImage cropMask=ImageIO.read(new ByteArrayInputStream(focus.get(0).maskPng()));
        assertEquals(crop.getWidth(),cropMask.getWidth());
        assertTrue(Math.max(crop.getWidth(),crop.getHeight())>=EditImagePreparer.MODEL_MIN_LONG_SIDE);

        // The focused edit erases them; the far line keeps the first edit and the rest stays original.
        int cw=crop.getWidth(), ch=crop.getHeight();
        BufferedImage generated=new BufferedImage(cw,ch,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<ch;y++) for(int x=0;x<cw;x++) generated.setRGB(x,y,new Color(200,190,60).getRGB());
        BufferedImage done=preparer.blendFocus(firstEdit,focus.get(0),encode(generated,"png"));
        assertTrue(preparer.focusRequests(prepared,done).isEmpty());
        assertEquals(firstEdit.getRGB(860,460),done.getRGB(860,460));
        assertEquals(source.getRGB(500,300),done.getRGB(500,300));
        // Nothing readable left after a clean first edit.
        BufferedImage clean=copy(source);
        for(int[] line:lines) fill(clean,line[0],line[1],120,24,new Color(200,190,60));
        assertTrue(preparer.focusRequests(prepared,clean).isEmpty());
    }

    @Test void focusedEditThatPaintsADarkHoleIsNotUsed() throws Exception {
        int size=400;
        BufferedImage source=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) source.setRGB(x,y,((x/4)%2==0 && x>=160 && x<240 && y>=180 && y<220)
                ? Color.BLACK.getRGB() : new Color(220,220,220).getRGB());
        Map<String,Object> cue=Map.of("id","c","type","text","coordinateSpace","analysis-normalized","bbox",List.of(.4,.45,.6,.55));
        var preparer=new EditImagePreparer();
        var prepared=preparer.prepare(new EditSubmission(UUID.randomUUID(),encode(source,"png"),"image/png",List.of(cue),"",50),
                EditImagePreparer.Framing.CROP);
        var focus=preparer.focusRequests(prepared,source);
        assertEquals(1,focus.size());
        BufferedImage crop=ImageIO.read(new ByteArrayInputStream(focus.get(0).imagePng()));
        BufferedImage hole=new BufferedImage(crop.getWidth(),crop.getHeight(),BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<hole.getHeight();y++) for(int x=0;x<hole.getWidth();x++) hole.setRGB(x,y,Color.BLACK.getRGB());
        assertSame(source,preparer.blendFocus(source,focus.get(0),encode(hole,"png")));
    }

    @Test void remainingWritingSeparatesErasedFromUntouchedWritingAndIgnoresTextureAround() {
        int w=800, h=400;
        var rect=new java.awt.Rectangle(200,160,400,40);
        var part=new PreparedEditImage.TextPart(Map.of(),new java.awt.geom.Path2D.Double(rect),rect);
        boolean[] region=new boolean[w*h];
        for(int y=rect.y;y<rect.y+rect.height;y++) for(int x=rect.x;x<rect.x+rect.width;x++) region[y*w+x]=true;
        short[] distance=EditComposite.distances(region,w,h,40);
        // Plain board: stripes of writing inside the line only.
        BufferedImage text=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB), plain=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            plain.setRGB(x,y,Color.WHITE.getRGB());
            text.setRGB(x,y,rect.contains(x,y) && (x/3)%2==0 ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
        }
        assertEquals(1.0,EditComposite.remainingWriting(text,text,part,distance,3,2),1e-9);
        assertEquals(0.0,EditComposite.remainingWriting(text,plain,part,distance,3,2),1e-9);
        assertEquals(0.0,EditComposite.remainingWriting(plain,text,part,distance,3,2),1e-9);   // nothing to erase
        // Leaves (a coarse texture everywhere) over the writing: erasing the writing leaves only the leaves.
        BufferedImage leafy=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB), leafyText=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            boolean leaf=((x/10)+(y/10))%2==0;
            leafy.setRGB(x,y,leaf ? new Color(30,90,30).getRGB() : Color.WHITE.getRGB());
            leafyText.setRGB(x,y,rect.contains(x,y) && (x/3)%2==0 ? Color.BLACK.getRGB() : leafy.getRGB(x,y));
        }
        assertTrue(EditComposite.remainingWriting(leafyText,leafy,part,distance,3,2)<0.1);
        assertTrue(EditComposite.remainingWriting(leafyText,leafyText,part,distance,3,2)>0.9);
    }

    @Test void nearLinesOfOneCueAreEditedAsOneAreaButFarOnesStaySeparate() throws Exception {
        // 200x200 photo, lines 10px tall: a slogan at y 40-50 and a year at y 80-90 with a stylized name between them
        // (gap 30px = 3 line heights), and a third line far below at y 170-180.
        Map<String,Object> cue = Map.of("id","synthetic","type","text","coordinateSpace","analysis-normalized",
                "bbox",List.of(.2,.2,.8,.25),
                "regions",List.of(line(.2,.2,.8,.25),line(.3,.4,.7,.45),line(.3,.85,.7,.9)));
        BufferedImage mask = mask(200,200,List.of(cue));
        assertTrue(cleared(mask,100,65));    // the name between the near lines
        assertTrue(cleared(mask,100,175));   // the far line itself
        assertFalse(cleared(mask,100,130));  // not the space between the near group and the far line
        var prepared = prepare(encode(new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB),"png"),"image/png",List.of(cue));
        assertEquals(2,prepared.textParts().size());
    }

    @Test void tallLettersGetARimForTheirShadowsThatGrowsWithTheLine() throws Exception {
        // 400x400 photo: a 60px tall sign line (y 100-160) and a 10px line (y 300-310). The fixed margin is 3px + 2px fade;
        // the tall line also gets 12px (20% of its height), the short one only 2px.
        Map<String,Object> tall = Map.of("id","a","type","text","coordinateSpace","analysis-normalized","bbox",List.of(.25,.25,.75,.4),
                "regions",List.of(line(.25,.25,.75,.4)));
        Map<String,Object> small = Map.of("id","b","type","text","coordinateSpace","analysis-normalized","bbox",List.of(.25,.75,.75,.775),
                "regions",List.of(line(.25,.75,.75,.775)));
        BufferedImage mask = mask(400,400,List.of(tall,small));
        assertTrue(cleared(mask,200,172));    // 12px below the tall line: its shadow
        assertFalse(cleared(mask,200,185));
        assertTrue(cleared(mask,200,315));
        assertFalse(cleared(mask,200,322));   // the short line's rim stays small
    }

    private Map<String,Object> line(double l,double t,double r,double b) {
        return Map.of("kind","text","verified",true,"bbox",List.of(l,t,r,b),
                "polygon",List.of(List.of(l,t),List.of(r,t),List.of(r,b),List.of(l,b)));
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

    @Test void darkHolePaintedOnABrightWallIsDetectedButADarkSurroundingIsNot() throws Exception {
        int size=400;
        var preparer=new EditImagePreparer();
        for(int wall : new int[]{210, 20}) {
            BufferedImage source=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<size;y++) for(int x=0;x<size;x++) source.setRGB(x,y,new Color(wall,wall,wall).getRGB());
            var prepared=preparer.prepare(new EditSubmission(UUID.randomUUID(),encode(source,"png"),"image/png",
                    List.of(cue(.4,.4,.6,.6)),"",50));
            BufferedImage hole=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
            BufferedImage clean=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
                boolean clue=x>=160 && x<240 && y>=160 && y<240;
                hole.setRGB(x,y,clue ? new Color(8,8,8).getRGB() : source.getRGB(x,y));
                clean.setRGB(x,y,source.getRGB(x,y));
            }
            double holeDefect=preparer.defect(prepared,encode(hole,"png"));
            assertEquals(0,preparer.defect(prepared,encode(clean,"png")),1e-9);
            if(wall>100) assertTrue(holeDefect>1,"hole defect "+holeDefect);
            else assertEquals(0,holeDefect,1e-9);
        }
    }

    @Test void blackHoleOverABrightAreaIsDetectedEvenWhenTheSurroundingsAreDarkInPlaces() throws Exception {
        // A building (bright, top half) over a dark arcade (bottom half): the edit area touches both, so the dark-patch
        // check stands down, but pure black painted over the bright facade is still a hole.
        int size=400;
        var preparer=new EditImagePreparer();
        BufferedImage source=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) source.setRGB(x,y,(y<200 ? new Color(200,200,195) : new Color(25,25,25)).getRGB());
        var prepared=preparer.prepare(new EditSubmission(UUID.randomUUID(),encode(source,"png"),"image/png",
                List.of(cue(0,0,.8,.6)),"",50));
        BufferedImage repainted=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB), torn=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
            int trees=new Color(70,110,60).getRGB();
            repainted.setRGB(x,y,x<320 && y<240 ? trees : source.getRGB(x,y));
            torn.setRGB(x,y,x<60 && y<60 ? Color.BLACK.getRGB() : repainted.getRGB(x,y));   // corner hole, 2.25% of the photo
        }
        assertTrue(preparer.defect(prepared,encode(repainted,"png"))<=1);
        assertTrue(preparer.defect(prepared,encode(torn,"png"))>1);
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
    private BufferedImage copy(BufferedImage image) {
        BufferedImage copy=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(image,0,0,null);
        return copy;
    }
    private void fill(BufferedImage image,int x0,int y0,int w,int h,Color color) {
        for(int y=y0;y<y0+h;y++) for(int x=x0;x<x0+w;x++) image.setRGB(x,y,color.getRGB());
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
