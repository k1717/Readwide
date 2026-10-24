package com.readwide.manager;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Makes legacy XHTML navigation XML self-contained without loading a DTD. */
final class EpubNavigationXml {
    // HTML 4 named character references. The XML predefined references are kept
    // verbatim below; the other names become numeric references, never markup.
    private static final Map<String, Integer> HTML4_ENTITIES = html4Entities();

    private EpubNavigationXml() { }

    static String prepare(String xml) throws IOException {
        StringBuilder out = null;
        int copied = 0;
        boolean inTag = false;
        boolean doctypeSeen = false;
        char quote = 0;
        for (int i = 0; i < xml.length();) {
            char c = xml.charAt(i);
            if (c == '<' && !inTag) {
                String ending = xml.startsWith("<!--", i) ? "-->"
                        : xml.startsWith("<![CDATA[", i) ? "]]>"
                        : xml.startsWith("<?", i) ? "?>" : null;
                if (ending != null) {
                    int end = xml.indexOf(ending, i + 2);
                    // Leave malformed constructs for the strict XML parser.
                    i = end < 0 ? xml.length() : end + ending.length();
                    continue;
                }
                if (xml.startsWith("<!DOCTYPE", i)) {
                    if (doctypeSeen) throw new IOException("Multiple DTD declarations");
                    doctypeSeen = true;
                    char dtdQuote = 0;
                    int end = i + 9;
                    for (; end < xml.length(); end++) {
                        char d = xml.charAt(end);
                        if (dtdQuote != 0) { if (d == dtdQuote) dtdQuote = 0; }
                        else if (d == '\'' || d == '"') dtdQuote = d;
                        else if (d == '[') throw new IOException("Internal DTD not supported");
                        else if (d == '>') break;
                    }
                    if (end == xml.length()) throw new IOException("Incomplete DTD");
                    if (out == null) out = new StringBuilder(xml.length());
                    out.append(xml, copied, i);
                    copied = end + 1;
                    i = copied;
                    continue;
                }
                inTag = true;
            } else if (inTag) {
                if (quote != 0) { if (c == quote) quote = 0; }
                else if (c == '\'' || c == '"') quote = c;
                else if (c == '>') inTag = false;
            }
            if (c == '&') {
                int end = i + 1;
                // HTML 4's longest name has eight characters. A bounded scan
                // avoids rescanning a malformed long run of ampersands.
                while (end < xml.length() && end - i <= 8
                        && isNameCharacter(xml.charAt(end))) end++;
                if (end < xml.length() && xml.charAt(end) == ';') {
                    String name = xml.substring(i + 1, end);
                    Integer codePoint = isXmlPredefined(name) ? null : HTML4_ENTITIES.get(name);
                    if (codePoint != null) {
                        if (out == null) out = new StringBuilder(xml.length());
                        out.append(xml, copied, i).append("&#").append(codePoint).append(';');
                        copied = end + 1;
                        i = copied;
                        continue;
                    }
                }
            }
            i++;
        }
        return out == null ? xml : out.append(xml, copied, xml.length()).toString();
    }

    private static boolean isNameCharacter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isXmlPredefined(String name) {
        return "amp".equals(name) || "lt".equals(name) || "gt".equals(name)
                || "quot".equals(name) || "apos".equals(name);
    }

    private static Map<String, Integer> html4Entities() {
        String data = "AElig=198;Aacute=193;Acirc=194;Agrave=192;Alpha=913;Aring=197;Atilde=195;Auml=196;Beta=914;Ccedil=199;Chi=935;Dagger=8225;"
                + "Delta=916;ETH=208;Eacute=201;Ecirc=202;Egrave=200;Epsilon=917;Eta=919;Euml=203;Gamma=915;Iacute=205;Icirc=206;Igrave=204;"
                + "Iota=921;Iuml=207;Kappa=922;Lambda=923;Mu=924;Ntilde=209;Nu=925;OElig=338;Oacute=211;Ocirc=212;Ograve=210;Omega=937;"
                + "Omicron=927;Oslash=216;Otilde=213;Ouml=214;Phi=934;Pi=928;Prime=8243;Psi=936;Rho=929;Scaron=352;Sigma=931;THORN=222;"
                + "Tau=932;Theta=920;Uacute=218;Ucirc=219;Ugrave=217;Upsilon=933;Uuml=220;Xi=926;Yacute=221;Yuml=376;Zeta=918;aacute=225;"
                + "acirc=226;acute=180;aelig=230;agrave=224;alefsym=8501;alpha=945;amp=38;and=8743;ang=8736;aring=229;asymp=8776;atilde=227;"
                + "auml=228;bdquo=8222;beta=946;brvbar=166;bull=8226;cap=8745;ccedil=231;cedil=184;cent=162;chi=967;circ=710;clubs=9827;"
                + "cong=8773;copy=169;crarr=8629;cup=8746;curren=164;dArr=8659;dagger=8224;darr=8595;deg=176;delta=948;diams=9830;divide=247;"
                + "eacute=233;ecirc=234;egrave=232;empty=8709;emsp=8195;ensp=8194;epsilon=949;equiv=8801;eta=951;eth=240;euml=235;euro=8364;"
                + "exist=8707;fnof=402;forall=8704;frac12=189;frac14=188;frac34=190;frasl=8260;gamma=947;ge=8805;gt=62;hArr=8660;harr=8596;"
                + "hearts=9829;hellip=8230;iacute=237;icirc=238;iexcl=161;igrave=236;image=8465;infin=8734;int=8747;iota=953;iquest=191;isin=8712;"
                + "iuml=239;kappa=954;lArr=8656;lambda=955;lang=9001;laquo=171;larr=8592;lceil=8968;ldquo=8220;le=8804;lfloor=8970;lowast=8727;"
                + "loz=9674;lrm=8206;lsaquo=8249;lsquo=8216;lt=60;macr=175;mdash=8212;micro=181;middot=183;minus=8722;mu=956;nabla=8711;"
                + "nbsp=160;ndash=8211;ne=8800;ni=8715;not=172;notin=8713;nsub=8836;ntilde=241;nu=957;oacute=243;ocirc=244;oelig=339;"
                + "ograve=242;oline=8254;omega=969;omicron=959;oplus=8853;or=8744;ordf=170;ordm=186;oslash=248;otilde=245;otimes=8855;ouml=246;"
                + "para=182;part=8706;permil=8240;perp=8869;phi=966;pi=960;piv=982;plusmn=177;pound=163;prime=8242;prod=8719;prop=8733;"
                + "psi=968;quot=34;rArr=8658;radic=8730;rang=9002;raquo=187;rarr=8594;rceil=8969;rdquo=8221;real=8476;reg=174;rfloor=8971;"
                + "rho=961;rlm=8207;rsaquo=8250;rsquo=8217;sbquo=8218;scaron=353;sdot=8901;sect=167;shy=173;sigma=963;sigmaf=962;sim=8764;"
                + "spades=9824;sub=8834;sube=8838;sum=8721;sup=8835;sup1=185;sup2=178;sup3=179;supe=8839;szlig=223;tau=964;there4=8756;"
                + "theta=952;thetasym=977;thinsp=8201;thorn=254;tilde=732;times=215;trade=8482;uArr=8657;uacute=250;uarr=8593;ucirc=251;ugrave=249;"
                + "uml=168;upsih=978;upsilon=965;uuml=252;weierp=8472;xi=958;yacute=253;yen=165;yuml=255;zeta=950;zwj=8205;zwnj=8204";
        Map<String, Integer> result = new HashMap<>();
        for (String item : data.split(";")) {
            int equals = item.indexOf('=');
            result.put(item.substring(0, equals), Integer.parseInt(item.substring(equals + 1)));
        }
        return Collections.unmodifiableMap(result);
    }
}
