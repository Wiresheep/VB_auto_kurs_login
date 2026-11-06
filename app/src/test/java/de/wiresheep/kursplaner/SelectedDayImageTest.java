package de.wiresheep.kursplaner;

import android.graphics.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class SelectedDayImageTest {
    private Bitmap image;
    private final Rect cell=new Rect(100,100,150,170);
    @Before public void setup() {
        image=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888); image.eraseColor(Color.rgb(29,26,39));
    }
    @After public void cleanup() { image.recycle(); }
    private void draw(Rect target,int background,int text) {
        var canvas=new Canvas(image); var paint=new Paint(); paint.setColor(background); canvas.drawRect(target,paint);
        paint.setColor(text); canvas.drawRect(target.left+10,target.top+10,target.left+20,target.top+20,paint);
    }
    @Test public void blackSelectionAndCyanDateTextConfirmTarget() {
        draw(cell,Color.BLACK,Color.rgb(0,169,220)); assertTrue(SelectedDayImage.selected(image,cell,320,480));
    }
    @Test public void unselectedWhiteDateTextDoesNotConfirmTarget() {
        draw(cell,Color.rgb(29,26,39),Color.WHITE); assertFalse(SelectedDayImage.selected(image,cell,320,480));
    }
    @Test public void blueDebugBoundsCannotConfirmAnUnselectedDay() {
        draw(cell,Color.BLACK,Color.rgb(76,125,245)); assertFalse(SelectedDayImage.selected(image,cell,320,480));
    }
    @Test public void selectedNeighbourDoesNotConfirmTarget() {
        draw(new Rect(170,100,220,170),Color.BLACK,Color.CYAN);
        assertFalse(SelectedDayImage.selected(image,cell,320,480));
    }
    @Test public void cyanTextWithoutBlackSelectionBackgroundIsInsufficient() {
        draw(cell,Color.rgb(29,26,39),Color.CYAN); assertFalse(SelectedDayImage.selected(image,cell,320,480));
    }
    @Test public void implausibleOrOutOfScreenBoundsAreRejected() {
        assertFalse(SelectedDayImage.selected(image,new Rect(-10,100,150,170),320,480));
        assertFalse(SelectedDayImage.selected(image,new Rect(0,0,300,400),320,480));
    }
}
