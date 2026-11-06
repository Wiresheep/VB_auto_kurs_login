package de.wiresheep.kursplaner;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

/** Checks VeniceBeach's black selection background and cyan date text in one known date cell. */
final class SelectedDayImage {
    private SelectedDayImage() {}
    static boolean selected(Bitmap image,Rect cell,int screenWidth,int screenHeight) {
        if(image==null || cell==null || cell.isEmpty() || screenWidth<=0 || screenHeight<=0
                || cell.left<0 || cell.top<0 || cell.right>screenWidth || cell.bottom>screenHeight
                || cell.width()>screenWidth/2 || cell.height()>screenHeight/3) return false;
        int left=(int)((long)cell.left*image.getWidth()/screenWidth);
        int right=(int)((long)cell.right*image.getWidth()/screenWidth);
        int top=(int)((long)cell.top*image.getHeight()/screenHeight);
        int bottom=(int)((long)cell.bottom*image.getHeight()/screenHeight);
        if(right-left<6 || bottom-top<6) return false;
        int black=0,cyan=0,total=0;
        for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) {
            int color=image.getPixel(x,y),r=Color.red(color),g=Color.green(color),b=Color.blue(color);
            total++;
            if(r<18 && g<18 && b<18) black++;
            if(r<100 && g>100 && b>120 && b>r+60 && g>r+40 && g>b*.68f) cyan++;
        }
        // Blue Android layout-debug outlines alone do not meet the cyan-text condition.
        return black>total*.12 && cyan>=Math.max(4,total*.004);
    }
}
