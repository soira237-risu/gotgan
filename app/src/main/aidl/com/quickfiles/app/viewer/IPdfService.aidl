package com.quickfiles.app.viewer;
import android.os.ParcelFileDescriptor;
interface IPdfService {
    int open(in ParcelFileDescriptor input);
    void render(int page, int width, in ParcelFileDescriptor output);
    void close();
}
