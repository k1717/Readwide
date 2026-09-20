package com.readwide.manager;

import android.content.Intent;
import android.view.View;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

/** Real loader with explicit controllable Android, parsing, worker and UI seams. */
public final class DocumentLoadLifecycleHostCheck {
    static int passed, failed;
    static void expect(boolean value){if(!value)throw new AssertionError();}
    static void check(String name,Runnable run){
        try{run.run();passed++;System.out.println("PASS "+name);}
        catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error.getClass().getSimpleName());}
    }
    static Intent book(String name){return new Intent().putExtra(DocumentPageActivity.EXTRA_FILE_PATH,name);}
    public static void main(String[] args){
        check("current failed book reports error and closes",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentPageLoadController loader=new DocumentPageLoadController(a);
            loader.loadFromIntent(book("bad.epub"));a.work.remove().run();a.ui.remove().run();
            expect(a.finishes==1&&a.errors==1&&!a.loading);
        });
        check("queued old error cannot close a replacement still loading",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentPageLoadController loader=new DocumentPageLoadController(a);
            loader.loadFromIntent(book("bad.epub"));a.work.remove().run();
            loader.loadFromIntent(book("good.epub"));a.ui.remove().run();
            expect(a.finishes==0&&a.errors==0&&a.loading);
            a.work.remove().run();a.ui.remove().run();
            expect(a.displays==1&&a.fileName.equals("good.epub")&&!a.loading);
        });
        check("queued old error cannot close an already displayed replacement",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentPageLoadController loader=new DocumentPageLoadController(a);
            loader.loadFromIntent(book("bad.epub"));a.work.remove().run();Runnable oldError=a.ui.remove();
            loader.loadFromIntent(book("good.epub"));a.work.remove().run();a.ui.remove().run();oldError.run();
            expect(a.finishes==0&&a.errors==0&&a.displays==1&&a.fileName.equals("good.epub"));
        });
        check("queued failure ignores destroyed host",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentPageLoadController loader=new DocumentPageLoadController(a);
            loader.loadFromIntent(book("bad.epub"));a.work.remove().run();a.activityDestroyed=true;a.ui.remove().run();
            expect(a.finishes==0&&a.errors==0);
        });
        check("current valid book displays normally",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentPageLoadController loader=new DocumentPageLoadController(a);
            loader.loadFromIntent(book("good.epub"));a.work.remove().run();a.ui.remove().run();
            expect(a.displays==1&&a.finishes==0&&a.errors==0&&!a.loading&&a.webView.visibility==View.VISIBLE);
        });
        check("queued old success cannot display over a new load",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentPageLoadController loader=new DocumentPageLoadController(a);
            loader.loadFromIntent(book("first.epub"));a.work.remove().run();
            loader.loadFromIntent(book("second.epub"));a.ui.remove().run();
            expect(a.displays==0&&a.loading);
            a.work.remove().run();a.ui.remove().run();expect(a.displays==1&&a.fileName.equals("second.epub"));
        });
        System.out.println("TOTAL: "+passed+" passed; "+failed+" failed");
        if(failed>0)System.exit(1);
    }
}

// Parsing and platform facades below are intentionally not production implementations.
class DocumentPageActivity {
    static final String EXTRA_FILE_PATH="path",EXTRA_FILE_URI="uri",EXTRA_JUMP_TO_PAGE="jump",EXTRA_MARKDOWN_SOURCE_OFFSET="md-offset",EXTRA_CONTENT_ANCHOR_JSON="anchor";
    int loadGeneration,documentAnchorPageGeneration,primaryDocumentPageReadyGeneration,currentPage;
    int markdownVisualCurrentPage,markdownVisualTotalPages,pendingMarkdownRestoreScrollY,pendingMarkdownRestorePage,pendingMarkdownRestoreSourceOffset,lastMarkdownSourceOffset,lastMarkdownSourceLine;
    boolean activityDestroyed,epubHasDocumentFont,epubFixedLayoutLike,epubImagePageLike,wordHasDocumentFont,loading;
    String wordDefaultFontFamily,documentFontOverride,markdownSourceText,lastMarkdownAnchorText,pendingDocumentRestoreAnchorJson,lastDocumentContentAnchorJson,filePath,fileName,docType;
    File localFile; final ArrayList<Object> pages=new ArrayList<>();final HashMap<String,Object> wordRelationships=new HashMap<>();
    final ArrayDeque<Runnable> work=new ArrayDeque<>(),ui=new ArrayDeque<>();
    final View webView=new View(),rightWebView=new View();final SavedBooks bookmarkManager=new SavedBooks();
    int finishes,errors,displays;
    void invalidateDocumentPageLoad(View view){}void closeResourceZip(){}
    void showLoadingWindow(){loading=true;}void hideLoadingWindow(){loading=false;}
    void hideDocumentSearchPanel(boolean save,boolean clear){}void clearDocumentSearchState(boolean clear){}
    void submitDocumentTask(Runnable task){work.add(task);}void runOnUiThread(Runnable task){ui.add(task);}
    void loadEpubPages(File file)throws IOException{if(file.getName().startsWith("bad"))throw new IOException("Bad book");pages.add(file.getName());}
    void loadMarkdownPage(File f)throws IOException{loadEpubPages(f);}void loadHwpPages(File f)throws IOException{loadEpubPages(f);}void loadWordPages(File f)throws IOException{loadEpubPages(f);}
    boolean isMarkdownDocument(){return "Markdown".equals(docType);}
    ActionBar getSupportActionBar(){return new ActionBar();}
    void applyDocumentTopPageStatusVisibility(){}void applyDocumentSystemBarColors(){}
    View findViewById(int id){return new View();}void showPage(int page,int direction){displays++;}
    String getString(int id){return "Error: ";}void finish(){finishes++;}
}
class SavedBooks {com.readwide.manager.model.ReaderState getReadingState(String path){return null;}}
class ActionBar {void setTitle(String title){}}
class ShortToast {static void show(DocumentPageActivity activity,String message){activity.errors++;}}
class R {static class id {static int document_root=1,document_content_column=2;}static class string {static int error_prefix=3;}}
