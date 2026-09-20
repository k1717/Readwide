package com.readwide.manager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Full production source/controller/math; explicit Android and plain-text extraction seams. */
public final class DocumentTtsHighlightHostCheck {
    static int passed, failed;
    static final StringBuilder cases = new StringBuilder("[");
    static void check(String name, Runnable run) {
        try {run.run();passed++;System.out.println("PASS " + name);}
        catch(Throwable e){failed++;System.out.println("FAIL " + name + ": " + e.getClass().getSimpleName());}
    }
    static void expect(boolean value){if(!value)throw new AssertionError();}
    static DocumentTtsTextSource source(DocumentPageActivity a,String... pages){
        return DocumentTtsTextSource.build(a, java.util.Arrays.stream(pages).map(DocumentPageActivity.Page::new).toList());
    }
    static String last(DocumentPageActivity a){return a.webView.scripts.get(a.webView.scripts.size()-1);}
    static void fixture(String name,String plain,String dom,String target,int sourceStart,int expectedStart,
                        int left,int right,int top,int bottom,boolean follow,boolean scroll){
        fixture(name,plain,dom,target,sourceStart,expectedStart,left,right,top,bottom,follow,scroll,0,0);
    }
    static void fixture(String name,String plain,String dom,String target,int sourceStart,int expectedStart,
                        int left,int right,int top,int bottom,boolean follow,boolean scroll,int offsetLeft,int offsetTop){
        DocumentPageActivity a=new DocumentPageActivity();a.markdown=!follow;
        DocumentTtsTextSource source=source(a,plain);
        source.setTtsHighlightRange(sourceStart,sourceStart+target.length());
        record(name,dom,last(a),expectedStart,left,right,top,bottom,scroll,offsetLeft,offsetTop);
    }
    static void record(String name,String dom,String js,int expectedStart,
                       int left,int right,int top,int bottom,boolean scroll){
        record(name,dom,js,expectedStart,left,right,top,bottom,scroll,0,0);
    }
    static void record(String name,String dom,String js,int expectedStart,
                       int left,int right,int top,int bottom,boolean scroll,int offsetLeft,int offsetTop){
        if(cases.length()>1)cases.append(',');
        cases.append("{name:").append(DocumentTtsHighlightMath.toJsStringLiteral(name))
             .append(",dom:").append(DocumentTtsHighlightMath.toJsStringLiteral(dom))
             .append(",js:").append(DocumentTtsHighlightMath.toJsStringLiteral(js))
             .append(",expectedStart:").append(expectedStart)
             .append(",rect:{left:").append(left).append(",right:").append(right)
             .append(",top:").append(top).append(",bottom:").append(bottom).append("}")
             .append(",offsetLeft:").append(offsetLeft).append(",offsetTop:").append(offsetTop)
             .append(",expectScroll:").append(scroll).append('}');
    }
    public static void main(String[] args)throws Exception{
        check("page replay retains the same source position",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentTtsTextSource s=source(a,"はい。別の文。はい。");
            s.setTtsHighlightRange(7,10);String before=last(a);a.highlighter.installScript();expect(last(a).equals(before));
        });
        check("page replay never paints a prior page sentence",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentTtsTextSource s=source(a,"はい。","はい。");
            s.setTtsHighlightRange(0,3);a.currentPage=1;a.webView.scripts.clear();a.highlighter.installScript();
            expect(a.webView.scripts.size()==1);
        });
        check("future page highlight waits until that page is displayed",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentTtsTextSource s=source(a,"はい。","はい。");
            a.webView.scripts.clear();s.setTtsHighlightRange(4,7);expect(a.webView.scripts.isEmpty());
            a.currentPage=1;a.highlighter.installScript();expect(a.webView.scripts.size()==2);
        });
        check("stop clears the pending page replay",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentTtsTextSource s=source(a,"はい。");
            s.setTtsHighlightRange(0,3);s.clearTtsHighlight();a.webView.scripts.clear();a.highlighter.installScript();
            expect(a.webView.scripts.size()==1);
        });
        check("empty source does not attempt a highlight",()->{
            DocumentPageActivity a=new DocumentPageActivity();DocumentTtsTextSource s=source(a);
            s.setTtsHighlightRange(0,20);expect(a.webView.scripts.isEmpty());
        });

        String repeated="はい。\n始めます。\nはい。\n終わります。";
        fixture("second repeated Japanese sentence",repeated,repeated,"はい。",repeated.lastIndexOf("はい。"),repeated.lastIndexOf("はい。"),10,40,20,60,true,false);
        fixture("restarted speech selects first occurrence",repeated,repeated,"はい。",0,0,10,40,20,60,true,false);
        DocumentPageActivity twoPageActivity=new DocumentPageActivity();
        String earlierPage="前の章。はい。",laterPage="新しい章。はい。次の文。はい。";
        DocumentTtsTextSource twoPageSource=source(twoPageActivity,earlierPage,laterPage);
        twoPageActivity.currentPage=1;
        int laterStart=earlierPage.length()+1+laterPage.lastIndexOf("はい。");
        twoPageSource.setTtsHighlightRange(laterStart,laterStart+3);
        record("absolute speech offset maps to second chapter",laterPage,last(twoPageActivity),laterPage.lastIndexOf("はい。"),10,40,20,60,false);
        String spaced="A\u00a0İ\u202fß\r\n はい。\u3000Xはい。";
        String domSpaced="Aİßはい。Xはい。";
        fixture("NBSP paragraph separators and Unicode case preserve offset",spaced,domSpaced,"はい。",spaced.lastIndexOf("はい。"),domSpaced.lastIndexOf("はい。"),10,40,20,60,true,false);
        String ruby="漢かん字じ。\nはい。漢かん字じ。\nはい。";
        fixture("ruby text and repeated sentence preserve offset",ruby,ruby,"はい。",ruby.lastIndexOf("はい。"),ruby.lastIndexOf("はい。"),10,40,20,60,true,false);
        fixture("vertical rl follows offscreen left sentence","はい。","はい。","はい。",0,0,-300,-250,20,60,true,true);
        fixture("vertical lr follows offscreen right sentence","はい。","はい。","はい。",0,0,900,950,20,60,true,true);
        fixture("panned visual viewport keeps fully visible sentence steady","はい。","はい。","はい。",0,0,850,900,500,540,true,false,300,200);
        fixture("panned visual viewport follows sentence left of visible frame","はい。","はい。","はい。",0,0,100,140,300,340,true,true,300,200);
        fixture("panned visual viewport follows sentence above visible frame","はい。","はい。","はい。",0,0,400,440,100,140,true,true,300,200);
        fixture("horizontal reading still follows below card","はい。","はい。","はい。",0,0,10,40,500,540,true,true);
        fixture("Markdown highlight respects disabled scrolling","はい。","はい。","はい。",0,0,-300,-250,20,60,false,false);
        String changed="\ufffc"+"a".repeat(80)+"はい。"+"b".repeat(80)+"次はい。末尾";
        fixture("unique context finds position after DOM text mismatch",changed,changed.substring(1),"はい。",changed.lastIndexOf("はい。"),changed.lastIndexOf("はい。")-1,10,40,20,60,true,false);
        String ambiguous="\ufffc"+"a".repeat(64)+"はい。"+"b".repeat(64)+"a".repeat(64)+"はい。"+"b".repeat(64);
        fixture("ambiguous changed DOM does not guess first repetition",ambiguous,ambiguous.substring(1),"はい。",ambiguous.lastIndexOf("はい。"),-1,-300,-250,20,60,true,false);
        fixture("missing source context does not jump to unrelated sentence","前はい。後","別はい。末","はい。",1,-1,-300,-250,20,60,true,false);
        String longSentence="a".repeat(45)+"本当の結末です。";
        fixture("partial long sentence does not highlight unrelated prefix",longSentence,"a".repeat(45)+"別の結末です。",longSentence,0,-1,-300,-250,20,60,true,false);
        Path out=Path.of(args[0]);
        var field=DocumentTtsHighlightController.class.getDeclaredField("HIGHLIGHT_SCRIPT");field.setAccessible(true);
        Files.writeString(out.resolve("install.js"),(String)field.get(null));
        Files.writeString(out.resolve("cases.js"),cases.append(']').toString());
        System.out.println("TOTAL: "+passed+" passed; "+failed+" failed");
        if(failed>0)System.exit(1);
    }
}
