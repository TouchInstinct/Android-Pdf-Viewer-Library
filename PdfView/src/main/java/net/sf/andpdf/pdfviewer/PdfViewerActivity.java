package net.sf.andpdf.pdfviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sun.pdfview.PDFFile;
import com.sun.pdfview.PDFImage;
import com.sun.pdfview.PDFPage;
import com.sun.pdfview.PDFPaint;
import com.sun.pdfview.decrypt.PDFAuthenticationFailureException;
import com.sun.pdfview.decrypt.PDFPassword;
import com.sun.pdfview.font.PDFFont;

import net.sf.andpdf.nio.ByteBuffer;
import net.sf.andpdf.pdfviewer.gui.FullScrollView;
import net.sf.andpdf.pdfviewer.gui.PdfView;
import net.sf.andpdf.refs.HardReference;

import java.io.File;
import java.io.IOException;

/**
 * U:\Android\android-sdk-windows-1.5_r1\tools\adb push u:\Android\simple_T.pdf /data/test.pdf
 *
 * @author ferenc.hechler
 */
public class PdfViewerActivity extends Activity {

    public static final String BUNDLE_KEY = "BUNDLE_KEY";

    private static final int STARTPAGE = 1;
    private static final float STARTZOOM = 1.0f;

    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 3.0f;
    private static final float ZOOM_INCREMENT = 1.5f;

    private static final String TAG = "PDFVIEWER";

    public static final String EXTRA_SHOWIMAGES = "net.sf.andpdf.extra.SHOWIMAGES";
    public static final String EXTRA_ANTIALIAS = "net.sf.andpdf.extra.ANTIALIAS";
    public static final String EXTRA_USEFONTSUBSTITUTION = "net.sf.andpdf.extra.USEFONTSUBSTITUTION";

    public static final boolean DEFAULTSHOWIMAGES = true;
    public static final boolean DEFAULTANTIALIAS = true;
    public static final boolean DEFAULTUSEFONTSUBSTITUTION = false;

    private final static int MENU_NEXT_PAGE = 1;
    private final static int MENU_PREV_PAGE = 2;
    private final static int MENU_GOTO_PAGE = 3;
    private final static int MENU_ZOOM_IN = 4;
    private final static int MENU_ZOOM_OUT = 5;
    private final static int MENU_BACK = 6;
    private final static int MENU_CLEANUP = 7;

    private final static int DIALOG_PAGENUM = 1;

    private GraphView mOldGraphView;
    private GraphView mGraphView;
    private PDFFile mPdfFile;
    public static byte[] byteArray;
    private int mPage;
    private float mZoom;
    private File mTmpFile;
    private ProgressDialog progress;

    private PDFPage mPdfPage;

    private Thread backgroundThread;
    private Handler uiHandler;

    /**
     * restore member variables from previously saved instance
     *
     * @return true if instance to restore from was found
     * @see
     */
    private boolean restoreInstance() {
        mOldGraphView = null;
        Log.e(TAG, "restoreInstance");
        if (getLastNonConfigurationInstance() == null) return false;
        PdfViewerActivity inst = (PdfViewerActivity) getLastNonConfigurationInstance();
        if (inst != this) {
            Log.e(TAG, "restoring Instance");
            mOldGraphView = inst.mGraphView;
            mPage = inst.mPage;
            mPdfFile = inst.mPdfFile;
            mPdfPage = inst.mPdfPage;
            mTmpFile = inst.mTmpFile;
            mZoom = inst.mZoom;
            backgroundThread = inst.backgroundThread;
        }
        return true;
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        uiHandler = new Handler();
        restoreInstance();

        if (mOldGraphView != null) {
            mGraphView = new GraphView(this);
            mGraphView.mBi = mOldGraphView.mBi;
            mOldGraphView = null;
            mGraphView.mImageView.setImageBitmap(mGraphView.mBi);
            //            mGraphView.updateTexts();
            setContentView(mGraphView);
        } else {
            mGraphView = new GraphView(this);
            Intent intent = getIntent();
            Log.i(TAG, "" + intent);

            PDFImage.sShowImages = getIntent().getBooleanExtra(PdfViewerActivity.EXTRA_SHOWIMAGES, PdfViewerActivity.DEFAULTSHOWIMAGES);
            PDFPaint.s_doAntiAlias = getIntent().getBooleanExtra(PdfViewerActivity.EXTRA_ANTIALIAS, PdfViewerActivity.DEFAULTANTIALIAS);
            PDFFont.sUseFontSubstitution = getIntent().getBooleanExtra(PdfViewerActivity.EXTRA_USEFONTSUBSTITUTION,
                    PdfViewerActivity.DEFAULTUSEFONTSUBSTITUTION);
            HardReference.sKeepCaches = true;

            mPage = STARTPAGE;
            mZoom = STARTZOOM;

            setContent(null);
        }
    }

    private void setContent(String password) {
        try {
            openFile(byteArray, password);
            pdfView.setmPdfFile(mPdfFile);
            setContentView(mGraphView);
            startRenderThread(mPage, mZoom);
        } catch (PDFAuthenticationFailureException e) {
            setContentView(getPdfPasswordLayoutResource());
            final EditText etPW = (EditText) findViewById(getPdfPasswordEditField());
            Button btOK = (Button) findViewById(getPdfPasswordOkButton());
            Button btExit = (Button) findViewById(getPdfPasswordExitButton());
            btOK.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    String pw = etPW.getText().toString();
                    setContent(pw);
                }
            });
            btExit.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });
        } catch (Exception ex) {
            Log.e(TAG, "an unexpected exception occurred");
        }
    }

    private synchronized void startRenderThread(final int page, final float zoom) {
        if (backgroundThread != null) return;
        backgroundThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (mPdfFile != null) {
                        showPage(page, zoom);
                    }
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage(), e);
                }
                backgroundThread = null;
            }
        });
        updateImageStatus();
        backgroundThread.start();
    }

    private void updateImageStatus() {
        if (backgroundThread == null) {
            return;
        }
        mGraphView.postDelayed(new Runnable() {
            public void run() {
                updateImageStatus();
            }
        }, 1000);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(Menu.NONE, MENU_PREV_PAGE, Menu.NONE, "Previous Page").setIcon(getPreviousPageImageResource());
        menu.add(Menu.NONE, MENU_NEXT_PAGE, Menu.NONE, "Next Page").setIcon(getNextPageImageResource());
        menu.add(Menu.NONE, MENU_GOTO_PAGE, Menu.NONE, "Goto Page");
        menu.add(Menu.NONE, MENU_ZOOM_OUT, Menu.NONE, "Zoom Out").setIcon(getZoomOutImageResource());
        menu.add(Menu.NONE, MENU_ZOOM_IN, Menu.NONE, "Zoom In").setIcon(getZoomInImageResource());
        if (HardReference.sKeepCaches) menu.add(Menu.NONE, MENU_CLEANUP, Menu.NONE, "Clear Caches");

        return true;
    }

    /**
     * Called when a menu item is selected.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);
        switch (item.getItemId()) {
            case MENU_NEXT_PAGE: {
                nextPage();
                break;
            }
            case MENU_PREV_PAGE: {
                prevPage();
                break;
            }
            case MENU_GOTO_PAGE: {
                gotoPage();
                break;
            }
            case MENU_ZOOM_IN: {
                zoomIn();
                break;
            }
            case MENU_ZOOM_OUT: {
                zoomOut();
                break;
            }
            case MENU_BACK: {
                finish();
                break;
            }
            case MENU_CLEANUP: {
                HardReference.cleanup();
                break;
            }
        }
        return true;
    }

    private void zoomIn() {
        if (mPdfFile != null) {
            if (mZoom < MAX_ZOOM) {
                mZoom *= ZOOM_INCREMENT;
                if (mZoom > MAX_ZOOM) mZoom = MAX_ZOOM;

                if (mZoom >= MAX_ZOOM) {
                    Log.d(TAG, "Disabling zoom in button");
                    mGraphView.bZoomIn.setEnabled(false);
                } else {
                    mGraphView.bZoomIn.setEnabled(true);
                }

                mGraphView.bZoomOut.setEnabled(true);

                startRenderThread(mPage, mZoom);
            }
        }
    }

    private void zoomOut() {
        if (mPdfFile != null) {
            if (mZoom > MIN_ZOOM) {
                mZoom /= ZOOM_INCREMENT;
                if (mZoom < MIN_ZOOM) mZoom = MIN_ZOOM;

                if (mZoom <= MIN_ZOOM) {
                    Log.d(TAG, "Disabling zoom out button");
                    mGraphView.bZoomOut.setEnabled(false);
                } else {
                    mGraphView.bZoomOut.setEnabled(true);
                }

                mGraphView.bZoomIn.setEnabled(true);

                startRenderThread(mPage, mZoom);
            }
        }
    }

    private void nextPage() {
        if (mPdfFile != null) {
            if (mPage < mPdfFile.getNumPages()) {
                mPage += 1;
                mGraphView.bZoomOut.setEnabled(true);
                mGraphView.bZoomIn.setEnabled(true);
                progress = ProgressDialog.show(PdfViewerActivity.this, "Loading", "Loading PDF Page " + mPage, true, true);
                startRenderThread(mPage, mZoom);
            }
        }
    }

    private void prevPage() {
        if (mPdfFile != null) {
            if (mPage > 1) {
                mPage -= 1;
                mGraphView.bZoomOut.setEnabled(true);
                mGraphView.bZoomIn.setEnabled(true);
                progress = ProgressDialog.show(PdfViewerActivity.this, "Loading", "Loading PDF Page " + mPage, true, true);
                startRenderThread(mPage, mZoom);
            }
        }
    }

    private void gotoPage() {
        if (mPdfFile != null) {
            showDialog(DIALOG_PAGENUM);
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_PAGENUM:
                LayoutInflater factory = LayoutInflater.from(this);
                final View pagenumView = factory.inflate(getPdfPageNumberResource(), null);
                final EditText edPagenum = (EditText) pagenumView.findViewById(getPdfPageNumberEditField());
                edPagenum.setText(Integer.toString(mPage));
                edPagenum.setOnEditorActionListener(new TextView.OnEditorActionListener() {

                    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                        if (event == null || (event.getAction() == 1)) {
                            // Hide the keyboard
                            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(edPagenum.getWindowToken(), 0);
                        }
                        return true;
                    }
                });
                return new AlertDialog.Builder(this)
                        //.setIcon(R.drawable.icon)
                        .setTitle("Jump to page")
                        .setView(pagenumView)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                String strPagenum = edPagenum.getText().toString();
                                int pageNum = mPage;
                                try {
                                    pageNum = Integer.parseInt(strPagenum);
                                } catch (NumberFormatException ignore) {
                                }
                                if ((pageNum != mPage) && (pageNum >= 1) && (pageNum <= mPdfFile.getNumPages())) {
                                    mPage = pageNum;
                                    mGraphView.bZoomOut.setEnabled(true);
                                    mGraphView.bZoomIn.setEnabled(true);
                                    progress =
                                            ProgressDialog.show(PdfViewerActivity.this, "Loading", "Loading PDF Page " + mPage, true, true);
                                    startRenderThread(mPage, mZoom);
                                }
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                            }
                        })
                        .create();
        }
        return null;
    }

    //TODO
    PdfView pdfView;

    private class GraphView extends FullScrollView {
        public Bitmap mBi;
        public ImageView mImageView;
        public Button mBtPage;

        ImageButton bZoomOut;
        ImageButton bZoomIn;

        public GraphView(Context context) {
            super(context);

            LinearLayout.LayoutParams lpWrap1 =
                    new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            LinearLayout.LayoutParams lpWrap10 =
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);

            LinearLayout.LayoutParams matchLp =
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

            LinearLayout vl = new LinearLayout(context);
            vl.setLayoutParams(lpWrap10);
            vl.setOrientation(LinearLayout.VERTICAL);

            if (mOldGraphView == null) {
                progress = ProgressDialog.show(PdfViewerActivity.this, "Loading", "Loading PDF Page", true, true);
            }
            //TODO
            pdfView = new PdfView(PdfViewerActivity.this);

            addNavButtons(vl);

            mImageView = new ImageView(context);
            setPageBitmap(null);
            updateImage();
            mImageView.setLayoutParams(lpWrap1);
            vl.addView(mImageView);
            vl.addView(pdfView);
            pdfView.setLayoutParams(matchLp);

            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            setBackgroundColor(Color.LTGRAY);
            setHorizontalScrollBarEnabled(true);
            setHorizontalFadingEdgeEnabled(true);
            setVerticalScrollBarEnabled(true);
            setVerticalFadingEdgeEnabled(true);
            addView(vl);
        }

        private void addNavButtons(ViewGroup vg) {

            LinearLayout.LayoutParams lpChild1 =
                    new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            LinearLayout.LayoutParams lpWrap10 =
                    new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

            Context context = vg.getContext();
            LinearLayout hl = new LinearLayout(context);
            hl.setLayoutParams(lpWrap10);
            hl.setOrientation(LinearLayout.HORIZONTAL);

            // zoom out button
            bZoomOut = new ImageButton(context);
            bZoomOut.setBackgroundDrawable(null);
            bZoomOut.setLayoutParams(lpChild1);
            bZoomOut.setImageResource(getZoomOutImageResource());
            bZoomOut.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    zoomOut();
                }
            });
            hl.addView(bZoomOut);

            // zoom in button
            bZoomIn = new ImageButton(context);
            bZoomIn.setBackgroundDrawable(null);
            bZoomIn.setLayoutParams(lpChild1);
            bZoomIn.setImageResource(getZoomInImageResource());
            bZoomIn.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    zoomIn();
                }
            });
            hl.addView(bZoomIn);

            // prev button
            ImageButton bPrev = new ImageButton(context);
            bPrev.setBackgroundDrawable(null);
            bPrev.setLayoutParams(lpChild1);
            //bPrev.setText("<");
            //bPrev.setWidth(40);
            bPrev.setImageResource(getPreviousPageImageResource());
            bPrev.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    prevPage();
                    updatePageNumber();
                }
            });
            hl.addView(bPrev);

            // page button
            mBtPage = new Button(context);
            mBtPage.setLayoutParams(lpChild1);

            mBtPage.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    gotoPage();
                    updatePageNumber();
                }
            });
            hl.addView(mBtPage);

            // next button
            ImageButton bNext = new ImageButton(context);
            bNext.setBackgroundDrawable(null);
            bNext.setLayoutParams(lpChild1);
            bNext.setImageResource(getNextPageImageResource());
            bNext.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    nextPage();
                    updatePageNumber();
                }
            });
            hl.addView(bNext);

            vg.addView(hl);

            addSpace(vg, 6, 6);
        }

        private void addSpace(ViewGroup vg, int width, int height) {
            TextView tvSpacer = new TextView(vg.getContext());
            tvSpacer.setLayoutParams(new LinearLayout.LayoutParams(width, height, 1));
            tvSpacer.setText(null);
            vg.addView(tvSpacer);
        }

        private void updateImage() {
            uiHandler.post(new Runnable() {
                public void run() {
                    mImageView.setImageBitmap(mBi);
                }
            });
        }

        private void setPageBitmap(Bitmap bi) {
            if (bi != null) {
                mBi = bi;
            }
        }

    }

    private void updatePageNumber() {
        Runnable updatePageNumber = new Runnable() {
            @Override
            public void run() {
                String maxPage = ((mPdfFile == null) ? "0" : Integer.toString(mPdfFile.getNumPages()));
                mGraphView.mBtPage.setText(mPage + "/" + maxPage);
            }
        };
        uiHandler.post(updatePageNumber);
    }

    private void showPage(int page, float zoom) throws Exception {
        pdfView.showPage(page, zoom);
        updatePageNumber();
        try {

            // Only load the page if it's a different page (i.e. not just changing the zoom level)
            if (mPdfPage == null || mPdfPage.getPageNumber() != page) {
                mPdfPage = mPdfFile.getPage(page, true);
            }

            float width = mPdfPage.getWidth();
            float height = mPdfPage.getHeight();
            RectF clip = null;
            Bitmap bi = mPdfPage.getImage((int) (width * zoom), (int) (height * zoom), clip, true, true);
            mGraphView.setPageBitmap(bi);
            mGraphView.updateImage();

            if (progress != null) progress.dismiss();
        } catch (Throwable e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    /**
     * <p>Open a specific pdf file.  Creates a DocumentInfo from the file,
     * and opens that.</p>
     * <p/>
     * <p><b>Note:</b> Mapping the file locks the file until the PDFFile
     * is closed.</p>
     *
     * @param byteArray the file to open
     * @throws IOException
     */
    public void openFile(final byte[] byteArray, String password) throws IOException {
        if (byteArray != null) {
            // now memory-map a byte-buffer
            ByteBuffer bb = ByteBuffer.NEW(byteArray);
            // create a PDFFile from the data
            if (password == null) {
                mPdfFile = new PDFFile(bb);
            } else {
                mPdfFile = new PDFFile(bb, new PDFPassword(password));
            }
        } else {
            Toast.makeText(this, "The error occurred", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTmpFile != null) {
            mTmpFile.delete();
            mTmpFile = null;
        }
        byteArray = null;
    }

    private int getPreviousPageImageResource() {
        return R.drawable.left_arrow;
    }

    private int getNextPageImageResource() {
        return R.drawable.right_arrow;
    }

    private int getZoomInImageResource() {
        return R.drawable.zoom_in;
    }

    private int getZoomOutImageResource() {
        return R.drawable.zoom_out;
    }

    private int getPdfPasswordLayoutResource() {
        return R.layout.pdf_file_password;
    }

    private int getPdfPageNumberResource() {
        return R.layout.dialog_pagenumber;
    }

    private int getPdfPasswordEditField() {
        return R.id.etPassword;
    }

    private int getPdfPasswordOkButton() {
        return R.id.btOK;
    }

    private int getPdfPasswordExitButton() {
        return R.id.btExit;
    }

    private int getPdfPageNumberEditField() {
        return R.id.pagenum_edit;
    }

}