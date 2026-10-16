package ru.aw.assistant.rules;

import java.io.*;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Optional local encoder. No native library, model or network is touched until an opted-in query. */
public final class SemanticSearch implements AutoCloseable {
    private record Artifact(String name,String url,long size,String sha256) {}
    private static final List<Artifact> FILES=List.of(
        new Artifact("frida-int8.onnx","https://github.com/AlpheusWorld/AWLauncher-releases/releases/download/awassistant-search-v1/frida-int8.onnx",32699796,"6839e61573be6adee64a252354a47b42a0aaabf6a78132885007f23291b92e62"),
        new Artifact("vocab.txt","https://huggingface.co/cointegrated/rubert-tiny2/resolve/e8ed3b0c8bbf4fb6984c3de043bf7d2f4e5969ae/vocab.txt",1080667,"f056a69b097422652053bf87565c35543e5d81540ca4b7dddd28de4157a969e0"),
        new Artifact("runtime.jar","https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.0/onnxruntime-1.31.0.jar",50942147,"d3f903af31fe8f01300adb6b2b67297aa5bee7895909afc32e76ef29a3921149"));
    private final Path folder;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"AWAssistant-semantic");t.setDaemon(true);return t;});
    private volatile boolean enabled,closed;
    private volatile String status="Поиск по смыслу выключен";
    private URLClassLoader runtime;
    private Object environment,session;
    private Class<?> tensorClass,environmentClass,valueClass;
    private Map<String,Integer> vocabulary;
    private String indexed="";
    private List<float[]> vectors=List.of();
    private record Passage(RuleBook.Rule rule,String text) {}
    private List<Passage> passages=List.of();
    private RuleSearch ruleIndex;
    private record Ranking(List<String> features,double[] weights,double threshold) {}
    private static Ranking ranking;
    private ScheduledFuture<?> pending,idle;
    private CompletableFuture<List<RuleSearch.Match>> pendingResult;
    public SemanticSearch(Path folder){this.folder=folder;}
    public String status(){return status;}
    public boolean enabled(){return enabled;}
    public static long downloadBytes(){return FILES.stream().mapToLong(Artifact::size).sum();}
    public synchronized void enabled(boolean value){
        if(closed||value==enabled)return;enabled=value;
        status=value?"Поиск по смыслу · модель загружается при запросе":"Поиск по смыслу выключен";
        if(!value){if(pending!=null)pending.cancel(false);if(pendingResult!=null)pendingResult.cancel(false);worker.execute(this::release);}
    }
    public synchronized CompletableFuture<List<RuleSearch.Match>> search(RuleBook book,String query,String category,List<RuleSearch.Match> lexical){
        if(!enabled||closed||query.isBlank()||RuleSearch.numberQuery(query)!=null)return CompletableFuture.completedFuture(lexical);
        if(pending!=null)pending.cancel(false);if(pendingResult!=null)pendingResult.cancel(false);
        var result=new CompletableFuture<List<RuleSearch.Match>>();pendingResult=result;
        pending=worker.schedule(()->{
            try{var similarities=similarities(book,query,category);result.complete(rank(query,ruleIndex,similarities));status="Поиск по смыслу · локально";
                synchronized(this){if(idle!=null)idle.cancel(false);idle=worker.schedule(()->{release();if(enabled)status="Поиск по смыслу · модель выгружена до следующего запроса";},90,TimeUnit.SECONDS);}
            }catch(CancellationException stopped){result.cancel(false);}
            catch(Exception|LinkageError failure){release();status="Модель недоступна · обычный поиск работает";result.completeExceptionally(failure);}
        },250,TimeUnit.MILLISECONDS);return result;
    }
    public static List<String> passages(RuleBook.Rule rule){
        String header=rule.title()+". "+String.join(", ",rule.keywords())+"\n";header=header.substring(0,Math.min(220,header.length()));
        String text=rule.description()+"\n"+rule.sanction();var result=new ArrayList<String>();result.add(header);
        for(int start=0;start<text.length();start+=500)result.add(header+text.substring(start,Math.min(text.length(),start+650)));
        return result.isEmpty()?List.of(header):List.copyOf(result);
    }
    Map<RuleBook.Rule,Float> similarities(RuleBook book,String query,String category) throws Exception {
        check();prepare();var key=hash(("passages-v3:"+FILES.getFirst().sha256()+new com.google.gson.Gson().toJson(book)).getBytes(StandardCharsets.UTF_8));
        if(!indexed.equals(key)){
            passages=book.rules().stream().flatMap(rule->passages(rule).stream().map(text->new Passage(rule,text))).toList();ruleIndex=new RuleSearch(book);
            vectors=readVectors(key,passages.size());
            if(vectors.isEmpty()){
                var prepared=new ArrayList<float[]>();for(var passage:passages){check();status="Подготавливаю поиск · "+prepared.size()+" / "+passages.size();prepared.add(embed("search_document: "+passage.text()));}
                vectors=List.copyOf(prepared);writeVectors(key,vectors);
            }indexed=key;
        }
        check();status="Сопоставляю вопрос с правилами";var queryVector=embed("search_query: "+query);var result=new LinkedHashMap<RuleBook.Rule,Float>();
        for(int i=0;i<passages.size();i++){var rule=passages.get(i).rule();if(category==null||category.equals(rule.category()))result.merge(rule,dot(queryVector,vectors.get(i)),Math::max);}
        return result;
    }
    private static synchronized Ranking ranking() {
        if(ranking!=null)return ranking;
        try(var input=SemanticSearch.class.getResourceAsStream("/assets/awassistant/search-ranking.json")){
            if(input==null)throw new IllegalStateException("Не найден ранжировщик правил");
            var loaded=new com.google.gson.Gson().fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),Ranking.class);
            if(!loaded.features().equals(RuleSearch.FEATURE_NAMES)||loaded.weights().length!=RuleSearch.FEATURE_NAMES.size()||!Double.isFinite(loaded.threshold())||loaded.threshold()<=0||loaded.threshold()>=1)throw new IllegalStateException("Некорректный ранжировщик правил");
            for(double weight:loaded.weights())if(!Double.isFinite(weight))throw new IllegalStateException("Некорректный вес ранжировщика");
            return ranking=loaded;
        }catch(IOException failure){throw new IllegalStateException("Не удалось открыть ранжировщик",failure);}
    }
    public static double relevance(String query,RuleSearch index,RuleBook.Rule rule,double cosine){
        var spec=ranking();var features=index.features(query,rule,cosine);double score=0;for(int i=0;i<features.length;i++)score+=features[i]*spec.weights()[i];return 1/(1+Math.exp(-Math.clamp(score,-40,40)));
    }
    public static List<RuleSearch.Match> rank(String query,RuleSearch index,Map<RuleBook.Rule,Float> similarities){
        var spec=ranking();return similarities.entrySet().stream().map(e->new RuleSearch.Match(e.getKey(),(int)Math.round(relevance(query,index,e.getKey(),e.getValue())*10000)))
            .filter(m->m.score()>=Math.round(spec.threshold()*10000)).sorted(Comparator.comparingInt(RuleSearch.Match::score).reversed()).limit(8).toList();
    }
    private void check(){if(!enabled||closed||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private void prepare() throws Exception {
        if(session!=null)return;Files.createDirectories(folder);
        for(var artifact:FILES){check();var file=folder.resolve(artifact.name());
            status="Проверяю файлы поиска";
            if(Files.isRegularFile(file)&&Files.size(file)==artifact.size()&&hashFile(file).equals(artifact.sha256()))continue;
            String label=artifact.name().endsWith("onnx")?"поисковую модель":artifact.name().endsWith("jar")?"компоненты поиска":"словарь";
            status="Скачиваю "+label;var part=Files.createTempFile(folder,"download-",".tmp");
            try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()){
                var request=HttpRequest.newBuilder(URI.create(artifact.url())).timeout(Duration.ofMinutes(5)).GET().build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
                try(var input=response.body();var output=Files.newOutputStream(part)){
                    if(response.statusCode()!=200)throw new IOException("Не удалось скачать модель");
                    byte[] buffer=new byte[65536];long size=0;int read;
                    while((read=input.read(buffer))!=-1){check();size+=read;if(size>artifact.size())throw new IOException("Слишком большой файл модели");output.write(buffer,0,read);status="Скачиваю "+label+" · "+size*100/artifact.size()+"%";}
                }
                if(Files.size(part)!=artifact.size()||!hashFile(part).equals(artifact.sha256()))throw new IOException("Не совпала контрольная сумма модели");
                Files.move(part,file,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(part);}
        }
        check();status="Загружаю локальную модель";
        if(runtime==null)runtime=new URLClassLoader(new java.net.URL[]{folder.resolve("runtime.jar").toUri().toURL()},ClassLoader.getPlatformClassLoader());
        environmentClass=runtime.loadClass("ai.onnxruntime.OrtEnvironment");tensorClass=runtime.loadClass("ai.onnxruntime.OnnxTensor");valueClass=runtime.loadClass("ai.onnxruntime.OnnxValue");
        environment=environmentClass.getMethod("getEnvironment",String.class).invoke(null,"AWAssistant");environmentClass.getMethod("setTelemetry",boolean.class).invoke(environment,false);
        var optionsClass=runtime.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
        try(var options=(AutoCloseable)optionsClass.getConstructor().newInstance()){
            optionsClass.getMethod("setIntraOpNumThreads",int.class).invoke(options,1);optionsClass.getMethod("setInterOpNumThreads",int.class).invoke(options,1);
            optionsClass.getMethod("setCPUArenaAllocator",boolean.class).invoke(options,false);
            optionsClass.getMethod("addConfigEntry",String.class,String.class).invoke(options,"session.intra_op.allow_spinning","0");
            session=environmentClass.getMethod("createSession",String.class,optionsClass).invoke(environment,folder.resolve(FILES.getFirst().name()).toString(),options);
        }
        var words=Files.readAllLines(folder.resolve("vocab.txt"));var map=new HashMap<String,Integer>();for(int i=0;i<words.size();i++)map.put(words.get(i),i);vocabulary=map;
    }
    private float[] embed(String text) throws Exception {
        long[][] ids={tokenize(text,vocabulary,256)},mask={new long[ids[0].length]},types={new long[ids[0].length]};Arrays.fill(mask[0],1);
        var create=tensorClass.getMethod("createTensor",environmentClass,Object.class);
        try(var input=(AutoCloseable)create.invoke(null,environment,ids);var attention=(AutoCloseable)create.invoke(null,environment,mask);var tokenTypes=(AutoCloseable)create.invoke(null,environment,types)){
            var inputs=new LinkedHashMap<String,Object>();inputs.put("input_ids",input);inputs.put("attention_mask",attention);
            if(((Set<?>)session.getClass().getMethod("getInputNames").invoke(session)).contains("token_type_ids"))inputs.put("token_type_ids",tokenTypes);
            try(var result=(AutoCloseable)session.getClass().getMethod("run",Map.class).invoke(session,inputs)){
                var output=result.getClass().getMethod("get",int.class).invoke(result,0);
                var array=(float[][][])valueClass.getMethod("getValue").invoke(output);var vector=new float[array[0][0].length];
                for(var token:array[0])for(int i=0;i<vector.length;i++)vector[i]+=token[i]/array[0].length;
                double norm=0;for(float value:vector)norm+=value*value;norm=Math.sqrt(norm);if(norm>0)for(int i=0;i<vector.length;i++)vector[i]/=(float)norm;return vector;
            }
        }
    }
    /** Case-sensitive BERT basic + greedy WordPiece, matching RuBERT-tiny2's tokenizer configuration. */
    public static long[] tokenize(String text,Map<String,Integer> vocabulary,int max){
        var pieces=new ArrayList<Long>();pieces.add((long)vocabulary.getOrDefault("[CLS]",101));
        var basic=new StringBuilder();var words=new ArrayList<String>();
        java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFC).codePoints().forEach(cp->{int type=Character.getType(cp);boolean punctuation=type==Character.CONNECTOR_PUNCTUATION||type==Character.DASH_PUNCTUATION||type==Character.START_PUNCTUATION||type==Character.END_PUNCTUATION||type==Character.OTHER_PUNCTUATION||type==Character.INITIAL_QUOTE_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION||(cp>=33&&cp<=47)||(cp>=58&&cp<=64)||(cp>=91&&cp<=96)||(cp>=123&&cp<=126);
            boolean chinese=Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN;
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||punctuation||chinese){if(!basic.isEmpty()){words.add(basic.toString());basic.setLength(0);}if(punctuation||chinese)words.add(new String(Character.toChars(cp)));}
            else if(!Character.isISOControl(cp)&&cp!=0&&cp!=0xfffd)basic.appendCodePoint(cp);
        });if(!basic.isEmpty())words.add(basic.toString());
        for(String word:words){if(pieces.size()>=max-1)break;var sub=new ArrayList<Long>();int start=0;
            if(word.codePointCount(0,word.length())>100)sub.add((long)vocabulary.getOrDefault("[UNK]",100));
            else while(start<word.length()){int end=word.length();Integer found=null;while(end>start){found=vocabulary.get((start==0?"":"##")+word.substring(start,end));if(found!=null)break;end=word.offsetByCodePoints(end,-1);}
                if(found==null){sub.clear();sub.add((long)vocabulary.getOrDefault("[UNK]",100));break;}sub.add(found.longValue());start=end;}
            for(long token:sub){if(pieces.size()>=max-1)break;pieces.add(token);}
        }pieces.add((long)vocabulary.getOrDefault("[SEP]",102));return pieces.stream().mapToLong(Long::longValue).toArray();
    }
    private static float dot(float[] a,float[] b){float value=0;for(int i=0;i<Math.min(a.length,b.length);i++)value+=a[i]*b[i];return value;}
    private static String hash(byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private String hashFile(Path file) throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int read;while((read=in.read(buffer))!=-1){check();digest.update(buffer,0,read);}}return HexFormat.of().formatHex(digest.digest());}
    private List<float[]> readVectors(String key,int count){
        var file=folder.resolve(key+".vectors");try{if(!Files.isRegularFile(file)||Files.size(file)>16_777_216)return List.of();
            try(var input=new DataInputStream(Files.newInputStream(file))){if(input.readInt()!=0x41575331||input.readInt()!=count)return List.of();var list=new ArrayList<float[]>();for(int i=0;i<count;i++){var v=new float[312];for(int d=0;d<v.length;d++){v[d]=input.readFloat();if(!Float.isFinite(v[d]))return List.of();}list.add(v);}return List.copyOf(list);}
        }catch(IOException e){return List.of();}
    }
    private void writeVectors(String key,List<float[]> values) throws IOException{
        var temp=Files.createTempFile(folder,"index-",".tmp");try{try(var out=new DataOutputStream(Files.newOutputStream(temp))){out.writeInt(0x41575331);out.writeInt(values.size());for(var v:values)for(float f:v)out.writeFloat(f);}Files.move(temp,folder.resolve(key+".vectors"),StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
        try(var files=Files.list(folder)){var old=files.filter(p->p.getFileName().toString().endsWith(".vectors")).sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).skip(8).toList();for(var path:old)Files.deleteIfExists(path);}
    }
    private void release(){if(session instanceof AutoCloseable resource)try{resource.close();}catch(Exception ignored){}session=null;environment=null;vocabulary=null;indexed="";vectors=List.of();passages=List.of();ruleIndex=null;}
    @Override public synchronized void close(){closed=true;enabled=false;if(pending!=null)pending.cancel(false);if(pendingResult!=null)pendingResult.cancel(false);worker.execute(()->{release();if(runtime!=null)try{runtime.close();}catch(IOException ignored){}});worker.shutdown();}
}
