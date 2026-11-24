package jp.haruya.novelstudio;

import org.json.JSONArray;
import org.json.JSONObject;

/** Voice identity comes from the provider catalog, not a guessed Android voice gender. */
final class SpeechCatalog {
    static final String DEFAULT_MODEL = "gemini-3.8-flash-lite-tts";
    static final String[] MODELS = {DEFAULT_MODEL, "gemini-3.8-flash-tts"};
    private static final String[][] VOICES = {
        {"Kore","female","芯がある"},{"Aoede","female","軽やか"},{"Leda","female","若々しい"},
        {"Zephyr","female","明るい"},{"Achernar","female","柔らかい"},{"Autonoe","female","明るい"},
        {"Callirrhoe","female","おおらか"},{"Despina","female","滑らか"},{"Erinome","female","明瞭"},
        {"Gacrux","female","成熟"},{"Laomedeia","female","快活"},{"Pulcherrima","female","はっきり"},
        {"Sulafat","female","温かい"},{"Vindemiatrix","female","穏やか"},
        {"Charon","male","落ち着いた説明"},{"Puck","male","快活"},{"Fenrir","male","感情豊か"},
        {"Orus","male","芯がある"},{"Achird","male","親しみ"},{"Algenib","male","かすれ"},
        {"Algieba","male","滑らか"},{"Alnilam","male","芯がある"},{"Enceladus","male","息遣い"},
        {"Iapetus","male","明瞭"},{"Rasalgethi","male","落ち着いた説明"},{"Sadachbia","male","生き生き"},
        {"Sadaltager","male","知的"},{"Schedar","male","均整"},{"Umbriel","male","おおらか"},
        {"Zubenelgenubi","male","くだけた声"}
    };
    static JSONArray voices() {
        JSONArray a=new JSONArray();for(String[] v:VOICES)a.put(NarrationStore.obj("id",v[0],"gender",v[1],"description",v[2]));return a;
    }
    static boolean voice(String s) {for(String[] v:VOICES)if(v[0].equals(s))return true;return false;}
    static boolean model(String s) {for(String v:MODELS)if(v.equals(s))return true;return false;}
}
