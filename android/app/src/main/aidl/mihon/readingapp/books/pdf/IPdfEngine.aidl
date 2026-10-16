package mihon.readingapp.books.pdf;
import android.os.ParcelFileDescriptor;
interface IPdfEngine {
    long openDocument(in ParcelFileDescriptor document);
    int pageCount(long session);
    void render(long session, int pageIndex, int width, in ParcelFileDescriptor output);
    void closeDocument(long session);
    int rendererUid();
}
