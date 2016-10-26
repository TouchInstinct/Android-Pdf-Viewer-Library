package net.sf.andpdf.pdfviewer;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import net.sf.andpdf.refs.HardReference;

import java.io.IOException;

import uk.co.senab.photoview.PhotoViewAttacher;

import static android.content.Context.INPUT_METHOD_SERVICE;

/**
 * U:\Android\android-sdk-windows-1.5_r1\tools\adb push u:\Android\simple_T.pdf /data/test.pdf
 *
 * @author ferenc.hechler
 */
public class PdfViewerFragment extends Fragment {

    private static final int STARTPAGE = 1;

    private static final String TAG = "PDFVIEWER";

    public static final boolean DEFAULTSHOWIMAGES = true;
    public static final boolean DEFAULTANTIALIAS = true;
    public static final boolean DEFAULTUSEFONTSUBSTITUTION = false;

    private GraphView mGraphView;
    private PDFFile mPdfFile;
    public static byte[] byteArray;
    private int mPage;
    private ProgressDialog progress;
    private TextView pageNumbersView;

    private PDFPage mPdfPage;

    private Thread backgroundThread;
    private Handler uiHandler;

    private boolean passwordNeeded;
    private String password;

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container, @Nullable final Bundle savedInstanceState) {
        uiHandler = new Handler();

        progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page", true, true);

        mGraphView = new GraphView(getActivity());
        PDFImage.sShowImages = PdfViewerFragment.DEFAULTSHOWIMAGES;
        PDFPaint.s_doAntiAlias = PdfViewerFragment.DEFAULTANTIALIAS;
        PDFFont.sUseFontSubstitution = PdfViewerFragment.DEFAULTUSEFONTSUBSTITUTION;
        HardReference.sKeepCaches = true;

        mPage = STARTPAGE;

        final Toolbar toolbar = (Toolbar) LayoutInflater.from(getActivity()).inflate(R.layout.pfd_toolbar, null);
        toolbar.setContentInsetsAbsolute(0, 0);
        toolbar.findViewById(R.id.pdf_toolbar_close_image).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                getFragmentManager().popBackStack();
            }
        });

        replaceView(((ActionBarHidden) getActivity()).getCurrentActivityToolbarReplacedBy(toolbar), toolbar);
        pageNumbersView = (TextView) toolbar.findViewById(R.id.pdf_toolbar_page_numbers_text_view);
        return setContent(password);
    }

    @Override
    public void onViewCreated(final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (view == null) {
            getFragmentManager().popBackStack();
            return;
        }

        if (!passwordNeeded) {
            startRenderThread(mPage);
        } else {
            hideProgressBar();
            final EditText etPW = (EditText) view.findViewById(getPdfPasswordEditField());
            Button btOK = (Button) view.findViewById(getPdfPasswordOkButton());
            Button btExit = (Button) view.findViewById(getPdfPasswordExitButton());
            btOK.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    password = etPW.getText().toString();
                    getFragmentManager()
                            .beginTransaction()
                            .detach(PdfViewerFragment.this)
                            .attach(PdfViewerFragment.this)
                            .commit();
                    hideSoftInput();
                }
            });
            btExit.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    hideSoftInput();
                    getFragmentManager().popBackStack();
                }
            });
        }
    }

    @Nullable
    private View setContent(@Nullable final String password) {
        try {
            openFile(byteArray, password);
            passwordNeeded = false;
            return mGraphView;
        } catch (PDFAuthenticationFailureException e) {
            passwordNeeded = true;
            return LayoutInflater.from(getActivity()).inflate(R.layout.pdf_file_password, null);
        } catch (Exception ex) {
            Log.e(TAG, "an unexpected exception occurred");
        }
        return null;
    }

    private synchronized void startRenderThread(final int page) {
        if (backgroundThread != null) return;
        backgroundThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (mPdfFile != null) {
                        showPage(page);
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

    private void nextPage() {
        if (mPdfFile != null) {
            if (mPage < mPdfFile.getNumPages()) {
                mPage += 1;
                updatePageNumbersView();
                progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page " + mPage, true, true);
                startRenderThread(mPage);
            }
        }
    }

    private void prevPage() {
        if (mPdfFile != null) {
            if (mPage > 1) {
                mPage -= 1;
                updatePageNumbersView();
                progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page " + mPage, true, true);
                startRenderThread(mPage);
            }
        }
    }

    private void updatePageNumbersView() {
        if (mPdfPage != null) {
            if (mPdfPage.getPageNumber() == mPdfFile.getNumPages() && mPdfPage.getPageNumber() == 1) {
                pageNumbersView.setVisibility(View.GONE);
            } else {
                pageNumbersView.setText(mPdfPage.getPageNumber() + "/" + mPdfFile.getNumPages());
            }
        }
    }

    /**
     * Hides device keyboard that is showing over {@link Activity}.
     * Do NOT use it if keyboard is over {@link android.app.Dialog} - it won't work as they have different {@link Activity#getWindow()}.
     */
    public void hideSoftInput() {
        if (getActivity().getCurrentFocus() == null) {
            return;
        }
        final InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(INPUT_METHOD_SERVICE);
        inputManager.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), 0);
        getActivity().getWindow().getDecorView().requestFocus();
    }

    private class GraphView extends FrameLayout {
        public Bitmap mBi;
        public ImageView pdfZoomedImageView;
        public PhotoViewAttacher photoViewAttacher;

        public GraphView(Context context) {
            super(context);

            final FrameLayout.LayoutParams frameLayout = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            frameLayout.gravity = Gravity.CENTER;
            pdfZoomedImageView = new ImageView(context);
            pdfZoomedImageView.setLayoutParams(frameLayout);
            pdfZoomedImageView.setBackgroundColor(getContext().getResources().getColor(R.color.zoomed_image_view_background));

            photoViewAttacher = new PhotoViewAttacher(pdfZoomedImageView);

            photoViewAttacher.setOnSingleFlingListener(new OnSwipeTouchListener() {

                @Override
                public void onSwipeLeft() {
                    nextPage();
                }

                @Override
                public void onSwipeRight() {
                    prevPage();
                }

            });
            setPageBitmap(null);
            //updateImage();

            final LinearLayout.LayoutParams linearLayout = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            setLayoutParams(linearLayout);
            addView(pdfZoomedImageView);
        }

        private void updateImage() {
            uiHandler.post(new Runnable() {
                public void run() {
                    pdfZoomedImageView.setImageBitmap(mBi);
                    photoViewAttacher.update();
                    updatePageNumbersView();
                }
            });
        }

        private void setPageBitmap(Bitmap bi) {
            if (bi != null) {
                mBi = bi;
            }
        }

    }

    // TODO: refactor
    private void showPage(final int page) {
        // on some Android getWidth() and getHeight() returns 0, so we need to wait until UI is ready
        mGraphView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // free memory from previous page
                    mGraphView.setPageBitmap(null);
                    mGraphView.updateImage();

                    // Only load the page if it's a different page (i.e. not just changing the zoom level)
                    if (mPdfPage == null || mPdfPage.getPageNumber() != page) {
                        mPdfPage = mPdfFile.getPage(page, true);
                    }

                    final int scale = 3;

                    double width = mPdfPage.getWidth() * scale;
                    double height = mPdfPage.getHeight() * scale;

                    int maxWidthToPopulate = mGraphView.getWidth();
                    int maxHeightToPopulate = mGraphView.getHeight();

                    int calculatedWidth;
                    int calculatedHeight;
                    final double widthRatio = width / maxWidthToPopulate;
                    final double heightRatio = height / maxHeightToPopulate;
                    if (width < maxWidthToPopulate && height < maxHeightToPopulate) {
                        if (widthRatio > heightRatio) {
                            calculatedWidth = (int) (width / widthRatio);
                            calculatedHeight = (int) (height / widthRatio);
                        } else {
                            calculatedWidth = (int) (width / heightRatio);
                            calculatedHeight = (int) (height / heightRatio);
                        }
                    } else {
                        if (widthRatio > 1 && heightRatio > 1) {
                            if (widthRatio > heightRatio) {
                                calculatedHeight = (int) (height / widthRatio);
                                calculatedWidth = (int) (width / widthRatio);
                            } else {
                                calculatedHeight = (int) (height / heightRatio);
                                calculatedWidth = (int) (width / heightRatio);
                            }
                        } else {
                            if (widthRatio > heightRatio) {
                                calculatedHeight = (int) (height / widthRatio);
                                calculatedWidth = (int) (width / widthRatio);
                            } else {
                                calculatedHeight = (int) (height / heightRatio);
                                calculatedWidth = (int) (width / heightRatio);
                            }
                        }
                    }

                    final Bitmap bitmap = mPdfPage.getImage(calculatedWidth, calculatedHeight, null, true, true);
                    mGraphView.setPageBitmap(bitmap);
                    mGraphView.updateImage();
                } catch (Throwable e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
        });

        hideProgressBar();

    }

    private void hideProgressBar() {
        if (progress != null) {
            progress.dismiss();
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
            Toast.makeText(getActivity(), "The error occurred", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        byteArray = null;
        if (mGraphView != null) {
            mGraphView.mBi = null;
            mGraphView.photoViewAttacher.cleanup();
        }
    }

    public static ViewGroup getParent(View view) {
        return (ViewGroup) view.getParent();
    }

    public static void removeView(View view) {
        ViewGroup parent = getParent(view);
        if (parent != null) {
            parent.removeView(view);
        }
    }

    public static void replaceView(View currentView, View newView) {
        ViewGroup parent = getParent(currentView);
        if (parent == null) {
            return;
        }
        final int index = parent.indexOfChild(currentView);
        removeView(currentView);
        parent.addView(newView, index);
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

    public interface ActionBarHidden {

        Toolbar getCurrentActivityToolbarReplacedBy(@NonNull final Toolbar toolbar);

    }

}