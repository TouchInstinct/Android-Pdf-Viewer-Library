package net.sf.andpdf.pdfviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import net.sf.andpdf.refs.HardReference;

import java.io.IOException;
import java.util.Locale;

import uk.co.senab.photoview.PhotoViewAttacher;

import static android.content.Context.INPUT_METHOD_SERVICE;

/**
 * U:\Android\android-sdk-windows-1.5_r1\tools\adb push u:\Android\simple_T.pdf /data/test.pdf
 *
 * @author ferenc.hechler
 */
public class PdfViewerFragment extends Fragment {

    private static final int STARTPAGE = 1;
    private static final float STARTZOOM = 1.0f;

    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 3.0f;
    private static final float ZOOM_INCREMENT = 1.5f;

    private static final String TAG = "PDFVIEWER";

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

    public static final String DIALOG_FRAGMENT_TAG_MARK = "DIALOG_FRAGMENT";

    private GraphView mOldGraphView;
    private GraphView mGraphView;
    private PDFFile mPdfFile;
    public static byte[] byteArray;
    private int mPage;
    private float mZoom;
    private ProgressDialog progress;

    private PDFPage mPdfPage;

    private Thread backgroundThread;
    private Handler uiHandler;

    private boolean passwordNeeded;
    private String password;

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container, @Nullable final Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        uiHandler = new Handler();
        restoreInstance();

        progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page", true, true);
        if (mOldGraphView != null) {
            mGraphView = new GraphView(getActivity());
            mGraphView.mBi = mOldGraphView.mBi;
            mOldGraphView = null;
            mGraphView.pdfZoomedImageView.setImageBitmap(mGraphView.mBi);
            mGraphView.updateTexts();
            return mGraphView;
        } else {
            mGraphView = new GraphView(getActivity());
            PDFImage.sShowImages = PdfViewerFragment.DEFAULTSHOWIMAGES;
            PDFPaint.s_doAntiAlias = PdfViewerFragment.DEFAULTANTIALIAS;
            PDFFont.sUseFontSubstitution = PdfViewerFragment.DEFAULTUSEFONTSUBSTITUTION;
            HardReference.sKeepCaches = true;

            mPage = STARTPAGE;
            mZoom = STARTZOOM;

            return setContent(password);
        }
    }

    @Override
    public void onViewCreated(final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (view == null) {
            getFragmentManager().popBackStack();
            return;
        }
        if (!passwordNeeded) {
            startRenderThread(mPage, mZoom);
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

    /**
     * restore member variables from previously saved instance
     *
     * @return true if instance to restore from was found
     * @see
     */
    private boolean restoreInstance() {
        mOldGraphView = null;
        Log.e(TAG, "restoreInstance");
        if (getActivity().getLastNonConfigurationInstance() == null) return false;
        PdfViewerFragment inst = (PdfViewerFragment) getActivity().getLastNonConfigurationInstance();
        if (inst != this) {
            Log.e(TAG, "restoring Instance");
            mOldGraphView = inst.mGraphView;
            mPage = inst.mPage;
            mPdfFile = inst.mPdfFile;
            mPdfPage = inst.mPdfPage;
            mZoom = inst.mZoom;
            backgroundThread = inst.backgroundThread;
        }
        return true;
    }

    private synchronized void startRenderThread(final int page, final float zoom) {
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
            mGraphView.updateUi();
            return;
        }
        mGraphView.updateUi();
        mGraphView.postDelayed(new Runnable() {
            public void run() {
                updateImageStatus();
            }
        }, 1000);
    }


    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.add(Menu.NONE, MENU_PREV_PAGE, Menu.NONE, "Previous Page").setIcon(getPreviousPageImageResource());
        menu.add(Menu.NONE, MENU_NEXT_PAGE, Menu.NONE, "Next Page").setIcon(getNextPageImageResource());
        menu.add(Menu.NONE, MENU_GOTO_PAGE, Menu.NONE, "Goto Page");
        menu.add(Menu.NONE, MENU_ZOOM_OUT, Menu.NONE, "Zoom Out").setIcon(getZoomOutImageResource());
        menu.add(Menu.NONE, MENU_ZOOM_IN, Menu.NONE, "Zoom In").setIcon(getZoomInImageResource());
        if (HardReference.sKeepCaches) {
            menu.add(Menu.NONE, MENU_CLEANUP, Menu.NONE, "Clear Caches");
        }
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
//            case MENU_GOTO_PAGE: {
//                gotoPage();
//                break;
//            }
            case MENU_ZOOM_IN: {
                zoomIn();
                break;
            }
            case MENU_ZOOM_OUT: {
                zoomOut();
                break;
            }
            case MENU_BACK: {
                getFragmentManager().popBackStack();
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
                progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page " + mPage, true, true);
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
                progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page " + mPage, true, true);
                startRenderThread(mPage, mZoom);
            }
        }
    }

    private void gotoPage() {
        if (mPdfFile != null) {
            final Bundle bundle = new Bundle();
            showDialogFragment(new GoToPageDialogFragment(), bundle);
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

    public class GoToPageDialogFragment extends DialogFragment {

        @NonNull
        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            LayoutInflater factory = LayoutInflater.from(getActivity());
            final View pagenumView = factory.inflate(R.layout.dialog_pagenumber, null);
            final EditText edPagenum = (EditText) pagenumView.findViewById(R.id.pagenum_edit);
            edPagenum.setText(String.format(Locale.getDefault(), "%d", mPage));
            edPagenum.setOnEditorActionListener(new TextView.OnEditorActionListener() {

                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (event == null || (event.getAction() == 1)) {
                        // Hide the keyboard
                        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(edPagenum.getWindowToken(), 0);
                    }
                    return true;
                }
            });
            return new AlertDialog.Builder(getActivity())
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
                                progress = ProgressDialog.show(getActivity(), "Loading", "Loading PDF Page " + mPage, true, true);
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
    }

    public void showDialogFragment(@NonNull final DialogFragment dialogFragment, @Nullable final Bundle bundle) {
        if (getFragmentManager().getFragments() != null) {
            for (final Fragment fragment : getFragmentManager().getFragments()) {
                // to fix bug with opening fragment several times on fast clicking
                if (fragment != null && fragment.getClass() == dialogFragment.getClass()) {
                    return;
                }
            }
        }
        if (bundle != null) {
            dialogFragment.setArguments(bundle);
        }
        dialogFragment.show(getFragmentManager(), dialogFragment.getClass().getName() + ";" + DIALOG_FRAGMENT_TAG_MARK);
    }

    private class GraphView extends FrameLayout {
        public Bitmap mBi;
        public ImageView pdfZoomedImageView;
        public PhotoViewAttacher photoViewAttacher;
        public Button mBtPage;
        private Button mBtPage2;

        ImageButton bZoomOut;
        ImageButton bZoomIn;

        public GraphView(Context context) {
            super(context);

            // TODO: temporarily commented
            //LinearLayout.LayoutParams lpWrap1 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1);
//            LinearLayout.LayoutParams lpWrap10 = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
//            LinearLayout vl = new LinearLayout(context);
//            vl.setLayoutParams(lpWrap10);
//            vl.setOrientation(LinearLayout.VERTICAL);

            // TODO: temporarily commented
            //addNavButtons(vl);
            // remember page button for updates
            mBtPage2 = mBtPage;

            final FrameLayout.LayoutParams linearLayout = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            linearLayout.gravity = Gravity.CENTER;
            pdfZoomedImageView = new ImageView(context);
            pdfZoomedImageView.setLayoutParams(linearLayout);
            pdfZoomedImageView.setBackgroundColor(getContext().getResources().getColor(R.color.zoomed_image_view_background));

            photoViewAttacher = new PhotoViewAttacher(pdfZoomedImageView);
            setPageBitmap(null);
            updateImage();

            // TODO: temporarily commented
//            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 100));
//            setBackgroundColor(Color.LTGRAY);
//            setHorizontalScrollBarEnabled(true);
//            setHorizontalFadingEdgeEnabled(true);
//            setVerticalScrollBarEnabled(true);
//            setVerticalFadingEdgeEnabled(true);

            addView(pdfZoomedImageView);

        }

        private void addNavButtons(ViewGroup vg) {

            LinearLayout.LayoutParams lpChild1 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1);
            LinearLayout.LayoutParams lpWrap10 = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 10);

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
                }
            });
            hl.addView(bPrev);

            // page button
            mBtPage = new Button(context);
            mBtPage.setLayoutParams(lpChild1);

            mBtPage.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    gotoPage();
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
                    pdfZoomedImageView.setImageBitmap(mBi);
                    photoViewAttacher.update();
                }
            });
        }

        private void updateUi() {
            uiHandler.post(new Runnable() {
                public void run() {
                    updateTexts();
                }
            });
        }

        private void setPageBitmap(Bitmap bi) {
            if (bi != null) {
                mBi = bi;
            }
        }

        protected void updateTexts() {
            if (mPdfPage != null) {
                if (mBtPage != null)
                    mBtPage.setText(mPdfPage.getPageNumber() + "/" + mPdfFile.getNumPages());
                if (mBtPage2 != null)
                    mBtPage2.setText(mPdfPage.getPageNumber() + "/" + mPdfFile.getNumPages());
            }
        }

    }

    // TODO: refactor
    private void showPage(int page) {
        try {
            // free memory from previous page
            mGraphView.setPageBitmap(null);
            mGraphView.updateImage();

            // Only load the page if it's a different page (i.e. not just changing the zoom level)
            if (mPdfPage == null || mPdfPage.getPageNumber() != page) {
                mPdfPage = mPdfFile.getPage(page, true);
            }

            final int scale = 3;

            float width = mPdfPage.getWidth() * scale;
            float height = mPdfPage.getHeight() * scale;

            int maxWidthToPopulate = mGraphView.getWidth();
            int maxHeightToPopulate = mGraphView.getHeight();


            int calculatedWidth;
            int calculatedHeight;
            final float widthRatio = width / maxWidthToPopulate;
            final float heightRatio = height / maxHeightToPopulate;
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

    private int getPdfPasswordEditField() {
        return R.id.etPassword;
    }

    private int getPdfPasswordOkButton() {
        return R.id.btOK;
    }

    private int getPdfPasswordExitButton() {
        return R.id.btExit;
    }

}