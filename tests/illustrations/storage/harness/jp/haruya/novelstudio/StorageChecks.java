package jp.haruya.novelstudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

public final class StorageChecks {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private interface Writer { void write(OutputStream output) throws Exception; }
    private static final class Entry {
        final String name;final Writer writer;
        Entry(String name,byte[] data){this(name,out->out.write(data));}
        Entry(String name,Writer writer){this.name=name;this.writer=writer;}
    }
    private static final class TestContext extends Context {
        final File files;
        TestContext(Path path)throws IOException {files=path.toFile();Files.createDirectories(path);}
        @Override public File getFilesDir(){return files;}
    }
    private static final class TrackedOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed=true; }
    }
    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(byte[] bytes) { super(bytes); }
        @Override public void close() { closed=true; }
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("java.awt.headless","true");ImageIO.setUseCache(false);
        Path temp=Files.createTempDirectory("novelstudio-illustration-checks-");
        try {
            TestContext aContext=new TestContext(temp.resolve("a"));
            TestContext bContext=new TestContext(temp.resolve("b"));
            IllustrationStore a=new IllustrationStore(aContext),b=new IllustrationStore(bContext);
            IllustrationBackup aBackup=new IllustrationBackup(a),bBackup=new IllustrationBackup(b);
            byte[] red=png(Color.RED,28,19),blue=png(Color.BLUE,28,19);
            JSONObject image=a.save(red,"import","保存時の指示\n二行目");
            String id=image.getString("id");
            ok(IllustrationStore.isValidId(id),"canonical UUID");
            ok("image/png".equals(image.getString("mime"))&&image.getInt("width")==28&&image.getInt("height")==19,"PNG dimensions and metadata");
            ok(pixel(a,id)==Color.RED.getRGB(),"stored pixels are readable");
            ByteArrayOutputStream copied=new ByteArrayOutputStream();a.copyTo(id,copied);
            ok(copied.size()==a.sizeOf(id),"binary export copies complete image");
            reject(()->a.open("../chatgpt.credentials"),"traversal ID rejected");
            reject(()->a.open(id.toUpperCase()),"uppercase/noncanonical UUID rejected");
            reject(()->a.save("<svg><script>bad()</script></svg>".getBytes(StandardCharsets.UTF_8),"import",""),"nonraster masquerading as image rejected");
            reject(()->a.save(new byte[IllustrationStore.MAX_IMAGE_BYTES+1],"import",""),"oversized image bytes rejected");
            reject(()->a.save(red,"import",repeat('p',IllustrationStore.MAX_PROMPT_CHARS+1)),"prompt limit rejects rather than truncates");
            reject(()->a.save(png(Color.GREEN,4097,1),"import",""),"image dimensions bounded before decode");
            JSONObject jpeg=a.save(jpeg(Color.ORANGE,33,21),"import","");
            ok(jpeg.getInt("width")==33&&read(a,jpeg.getString("id"))[0]==(byte)0x89,"JPEG normalizes to PNG");
            JSONObject resized=a.save(noiseJpeg(),"import","");
            ok(resized.getInt("width")<3000&&a.sizeOf(resized.getString("id"))<=IllustrationStore.MAX_IMAGE_BYTES,"PNG expansion triggers bounded resampling");
            JSONObject alpha=a.save(alphaPng(),"import","");
            ok(((pixel(a,alpha.getString("id"))>>>24)&255)==64,"alpha retained in PNG");

            String state=state(image).toString();
            Files.write(aContext.files.toPath().resolve("chatgpt.credentials.enc"),"CANARY_SECRET_NOT_FOR_BACKUP".getBytes(StandardCharsets.UTF_8));
            byte[] archive=export(aBackup,state);
            TrackedOutput trackedOutput=new TrackedOutput();aBackup.exportTo(trackedOutput,state);
            ok(!trackedOutput.closed,"export retains caller stream ownership");
            List<String> entries=names(archive);
            ok(entries.size()==2&&entries.contains("state.json")&&entries.contains("illustrations/"+id+".png"),"archive includes only referenced image and manuscript");
            ok(!new String(archive,StandardCharsets.ISO_8859_1).contains("CANARY_SECRET_NOT_FOR_BACKUP"),"private sibling files excluded");
            String restored=bBackup.importFrom(new ByteArrayInputStream(archive));
            TrackedInput trackedInput=new TrackedInput(archive);bBackup.importFrom(trackedInput);
            ok(!trackedInput.closed,"import retains caller stream ownership");
            String restoredId=firstImage(restored).getString("id");
            ok(id.equals(restoredId)&&pixel(b,restoredId)==Color.RED.getRGB(),"ZIP round trip preserves image and ID");
            ok(firstImage(restored).getString("prompt").equals("保存時の指示\n二行目"),"metadata prompt round trip");
            int before=fileCount(bContext.files.toPath().resolve("illustrations"));
            bBackup.importFrom(new ByteArrayInputStream(archive));
            ok(fileCount(bContext.files.toPath().resolve("illustrations"))==before,"same-content ID reused on repeat import");
            JSONObject shared=state(image);
            JSONObject sharedChapter=new JSONObject(shared.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).toString()).put("id","chapter_second");
            shared.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").put(sharedChapter);
            ok(names(export(aBackup,shared.toString())).size()==2,"same image in different chapters archived once");

            TestContext collisionContext=new TestContext(temp.resolve("collision"));
            IllustrationStore collisionStore=new IllustrationStore(collisionContext);
            JSONObject blueImage=collisionStore.save(blue,"import","");
            Files.write(collisionContext.files.toPath().resolve("illustrations").resolve(id+".png"),read(collisionStore,blueImage.getString("id")));
            String collisionState=new IllustrationBackup(collisionStore).importFrom(new ByteArrayInputStream(archive));
            String remapped=firstImage(collisionState).getString("id");
            ok(!id.equals(remapped)&&pixel(collisionStore,id)==Color.BLUE.getRGB()&&pixel(collisionStore,remapped)==Color.RED.getRGB(),"different-content ID collision remapped without overwrite");

            String missing=UUID.randomUUID().toString();
            ByteArrayOutputStream missingOutput=new ByteArrayOutputStream();
            reject(()->aBackup.exportTo(missingOutput,state(meta(missing)).toString()),"missing image aborts export");
            ok(missingOutput.size()==0,"missing-image preflight writes no partial ZIP");
            rejectImport(bBackup,zip(new Entry("state.json",state(meta(missing)).toString().getBytes(StandardCharsets.UTF_8))),bContext,"missing archive image rejected");
            rejectImport(bBackup,zip(new Entry("state.json",state.getBytes(StandardCharsets.UTF_8)),new Entry("../escaped.png",red)),bContext,"ZIP traversal rejected");
            ok(!Files.exists(temp.resolve("escaped.png")),"traversal created no outside file");
            rejectImport(bBackup,zip(new Entry("state.json",state.getBytes(StandardCharsets.UTF_8)),new Entry("illustrations/"+id+".png","not an image".getBytes(StandardCharsets.UTF_8))),bContext,"wrong actual image content rejected");
            String other=UUID.randomUUID().toString();
            byte[] duplicate=zip(new Entry("state.json",state.getBytes(StandardCharsets.UTF_8)),new Entry("illustrations/"+id+".png",red),new Entry("illustrations/"+other+".png",blue));
            duplicate=replace(duplicate,"illustrations/"+other+".png","illustrations/"+id+".png");
            rejectImport(bBackup,duplicate,bContext,"duplicate ZIP entry rejected");
            byte[] duplicateState=replace(zip(new Entry("state.json",state.getBytes(StandardCharsets.UTF_8)),new Entry("statx.json",new byte[]{'{','}'})),"statx.json","state.json");
            rejectImport(bBackup,duplicateState,bContext,"duplicate state entry rejected");
            rejectImport(bBackup,zip(new Entry("state.json",state.getBytes(StandardCharsets.UTF_8)),new Entry("illustrations/"+id+".png",out->pad(out,red,IllustrationStore.MAX_IMAGE_BYTES+1L))),bContext,"compressed entry exceeding 16MiB rejected");
            rejectImport(bBackup,zip(new Entry("state.json",out->pad(out,new byte[]{'{','}'},IllustrationBackup.MAX_STATE_BYTES+1L))),bContext,"compressed state exceeding 30MiB rejected");
            rejectImport(bBackup,zip(new Entry("state.json",new byte[]{'{','"','x','"',':','"',(byte)0xff,'"','}'})),bContext,"malformed UTF-8 manifest rejected");
            rejectImport(bBackup,zip(new Entry("state.json",state(image).put("app","OtherApp").toString().getBytes(StandardCharsets.UTF_8)),new Entry("illustrations/"+id+".png",red)),bContext,"wrong application manifest rejected before publication");

            JSONArray many=new JSONArray();for(int i=0;i<51;i++)many.put(meta(UUID.randomUUID().toString()));
            JSONObject tooMany=state(image);tooMany.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).put("illustrations",many);
            reject(()->aBackup.exportTo(new ByteArrayOutputStream(),tooMany.toString()),"per-chapter count bounded");
            JSONObject repeated=state(image);repeated.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).getJSONArray("illustrations").put(new JSONObject(image.toString()));
            reject(()->aBackup.exportTo(new ByteArrayOutputStream(),repeated.toString()),"duplicate image ID in chapter rejected");

            String goodNew=UUID.randomUUID().toString(),badNew=UUID.randomUUID().toString();
            JSONObject mixed=state(meta(goodNew));mixed.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).getJSONArray("illustrations").put(meta(badNew));
            rejectImport(bBackup,zip(new Entry("state.json",mixed.toString().getBytes(StandardCharsets.UTF_8)),new Entry("illustrations/"+goodNew+".png",red),new Entry("illustrations/"+badNew+".png",new byte[]{1,2,3})),bContext,"one invalid image prevents all image publication");
            ok(!b.exists(goodNew),"valid image from failed import was not committed");

            JSONArray largeRefs=new JSONArray();List<Entry> largeEntries=new ArrayList<>();
            for(int i=0;i<17;i++){String largeId=UUID.randomUUID().toString();largeRefs.put(meta(largeId));largeEntries.add(new Entry("illustrations/"+largeId+".png",out->pad(out,red,IllustrationStore.MAX_IMAGE_BYTES)));}
            JSONObject largeState=state(image);largeState.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).put("illustrations",largeRefs);
            largeEntries.add(0,new Entry("state.json",largeState.toString().getBytes(StandardCharsets.UTF_8)));
            rejectImport(bBackup,zip(largeEntries.toArray(new Entry[0])),bContext,"total expansion over 250MiB rejected");

            String symlinkId=UUID.randomUUID().toString();Path secret=temp.resolve("outside-private-data");Files.write(secret,new byte[]{7,8,9});
            Files.createSymbolicLink(aContext.files.toPath().resolve("illustrations").resolve(symlinkId+".png"),secret);
            reject(()->a.open(symlinkId),"image symlink cannot expose nonimage private file");
            ok(fileCount(bContext.files.toPath().resolve("illustration-staging"))==0,"all scoped staging directories cleaned");
            System.out.println("PASS "+checks+" illustration storage/backup assertions");
            System.out.println("Android EXIF parsing, WebP decoding and device memory behavior require device validation.");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
        }
    }
    private static byte[] png(Color color,int width,int height)throws IOException {BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setColor(color);g.fillRect(0,0,width,height);g.dispose();return encode(image,"png");}
    private static byte[] jpeg(Color color,int width,int height)throws IOException {BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(color);g.fillRect(0,0,width,height);g.dispose();return encode(image,"jpeg");}
    private static byte[] noiseJpeg()throws IOException {BufferedImage image=new BufferedImage(3000,3000,BufferedImage.TYPE_INT_RGB);java.util.Random random=new java.util.Random(37);for(int y=0;y<3000;y++)for(int x=0;x<3000;x++)image.setRGB(x,y,random.nextInt());return encode(image,"jpeg");}
    private static byte[] alphaPng()throws IOException {BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0x40ff0000);return encode(image,"png");}
    private static byte[] encode(BufferedImage image,String format)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,format,out);image.flush();return out.toByteArray();}
    private static String repeat(char c,int n){char[] v=new char[n];Arrays.fill(v,c);return new String(v);}
    private static JSONObject meta(String id)throws Exception {return new JSONObject().put("id",id).put("mime","image/png").put("width",28).put("height",19).put("source","import").put("prompt","").put("createdAt",1);}
    private static JSONObject state(JSONObject image)throws Exception {return new JSONObject().put("app","NovelStudio").put("schemaVersion",1).put("books",new JSONArray().put(new JSONObject().put("id","book_test").put("title","作品").put("chapters",new JSONArray().put(new JSONObject().put("id","chapter_test").put("title","話").put("body","本文").put("illustrations",new JSONArray().put(new JSONObject(image.toString())))))));}
    private static JSONObject firstImage(String state)throws Exception {return new JSONObject(state).getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).getJSONArray("illustrations").getJSONObject(0);}
    private static byte[] read(IllustrationStore store,String id)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();store.copyTo(id,out);return out.toByteArray();}
    private static int pixel(IllustrationStore store,String id)throws IOException {try(InputStream input=store.open(id)){return ImageIO.read(input).getRGB(0,0);}}
    private static byte[] export(IllustrationBackup backup,String state)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();backup.exportTo(out,state);return out.toByteArray();}
    private static byte[] zip(Entry... entries)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){for(Entry entry:entries){zip.putNextEntry(new ZipEntry(entry.name));entry.writer.write(zip);zip.closeEntry();}}return out.toByteArray();}
    private static List<String> names(byte[] archive)throws IOException {List<String> names=new ArrayList<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)){ZipEntry entry;while((entry=zip.getNextEntry())!=null){names.add(entry.getName());zip.closeEntry();}}return names;}
    private static byte[] replace(byte[] input,String from,String to){byte[] old=from.getBytes(StandardCharsets.UTF_8),replacement=to.getBytes(StandardCharsets.UTF_8),out=input.clone();if(old.length!=replacement.length)throw new IllegalArgumentException();for(int i=0;i<=out.length-old.length;i++){boolean match=true;for(int j=0;j<old.length;j++)if(out[i+j]!=old[j]){match=false;break;}if(match){System.arraycopy(replacement,0,out,i,replacement.length);i+=old.length-1;}}return out;}
    private static void pad(OutputStream output,byte[] prefix,long size)throws IOException {output.write(prefix);byte[] zeros=new byte[8192];long remaining=size-prefix.length;while(remaining>0){int n=(int)Math.min(remaining,zeros.length);output.write(zeros,0,n);remaining-=n;}}
    private static int fileCount(Path directory)throws IOException {if(!Files.isDirectory(directory))return 0;try(java.util.stream.Stream<Path> children=Files.list(directory)){return (int)children.count();}}
    private static void rejectImport(IllustrationBackup backup,byte[] bytes,TestContext context,String message)throws Exception {int before=fileCount(context.files.toPath().resolve("illustrations"));reject(()->backup.importFrom(new ByteArrayInputStream(bytes)),message);ok(fileCount(context.files.toPath().resolve("illustrations"))==before,message+"; prior store retained");ok(fileCount(context.files.toPath().resolve("illustration-staging"))==0,message+"; staging cleaned");}
    private static void reject(Action action,String message)throws Exception {try{action.run();throw new AssertionError(message);}catch(IOException expected){checks++;}}
    private static void ok(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
