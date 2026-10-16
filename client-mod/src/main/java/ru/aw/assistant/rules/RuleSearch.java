package ru.aw.assistant.rules;

import java.text.Normalizer;
import java.util.*;

/** Weighted BM25 index plus shared, server-independent relevance features. */
public final class RuleSearch {
    public record Match(RuleBook.Rule rule,int score) {}
    private record Entry(RuleBook.Rule rule,String title,Set<String> titleTerms,Set<String> keywordTerms,Map<String,Integer> frequencies,Set<String> topics,Set<String> subjectTopics,int length) {}
    public static final List<String> FEATURE_NAMES=List.of("bias","semantic","bm25","query_coverage","title_coverage","keyword_coverage","topic_recall","topic_precision","missing_topics","extra_topics","title_phrase","keyword_phrase","title_precision","has_topics");
    private static final Set<String> STOP=Set.of("а","и","или","в","во","на","за","по","с","со","к","у","из","для","при","ли","что","как","если","это","я","мне","меня","можно","нельзя","будет","могу","правило","пункт","статья","хочу","хотел","почему","какой","какое","какая","какие","сколько","просто","пожалуйста","чтобы","чтоб","когда","где","от","он","она","они","мы","вы","так","то","его","её","их","свой","себя","сам","самому","мой","моя","мои","всё","все","ещё","уже","про","после","перед","без","тут","там");
    private static final String[] ENDINGS={"иями","ями","ами","ого","его","ому","ему","ыми","ими","ов","ев","ам","ям","ах","ях","ой","ый","ий","ая","яя","ую","юю","ое","ее","ые","ие","ем","ом","а","я","ы","и","у","ю","е","о"};
    private static final java.util.regex.Pattern WORDS=java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final java.util.regex.Pattern RUSSIAN=java.util.regex.Pattern.compile("[а-я]+");
    private final List<Entry> entries;
    private final Map<RuleBook.Rule,Entry> byRule=new HashMap<>();
    private final Map<String,Integer> documentFrequency=new HashMap<>();
    private final double averageLength;
    public RuleSearch(RuleBook book){
        var result=new ArrayList<Entry>();long total=0;
        for(var rule:book.rules()){
            String parentTitles=book.rules().stream().filter(parent->rule.number().startsWith(parent.number()+".")).map(RuleBook.Rule::title).reduce("",(a,b)->a+" "+b);
            var tf=new HashMap<String,Integer>();
            add(tf,rule.description()+" "+rule.sanction(),1);add(tf,rule.title(),4);add(tf,String.join(" ",rule.keywords()),3);
            int length=tf.values().stream().mapToInt(Integer::intValue).sum();total+=length;
            String subject=parentTitles+" "+rule.title()+" "+String.join(" ",rule.keywords());var subjectTopics=topics(subject,false);
            var entry=new Entry(rule,normalize(rule.title()),terms(rule.title()),terms(String.join(" ",rule.keywords())),Map.copyOf(tf),topics(subject+" "+rule.description().lines().findFirst().orElse(""),false),subjectTopics,length);
            result.add(entry);byRule.put(rule,entry);for(var term:tf.keySet())documentFrequency.merge(term,1,Integer::sum);
        }
        entries=List.copyOf(result);averageLength=Math.max(1,total/(double)Math.max(1,entries.size()));
    }
    private static void add(Map<String,Integer> target,String text,int weight){for(String word:WORDS.split(normalize(text)))if(!word.isBlank()&&!STOP.contains(word))target.merge(stem(word),weight,Integer::sum);}
    private static Set<String> terms(String text){var result=new LinkedHashSet<String>();for(String word:WORDS.split(normalize(text)))if(!word.isBlank()&&!STOP.contains(word))result.add(stem(word));return Set.copyOf(result);}
    private static String stem(String word){if(word.length()<4||!RUSSIAN.matcher(word).matches())return word;for(String ending:ENDINGS)if(word.endsWith(ending)&&word.length()-ending.length()>=3)return word.substring(0,word.length()-ending.length());return word;}
    public static String normalize(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё','е').strip();}
    public static String numberQuery(String query){String text=normalize(query).replaceFirst("^(?:пункт|правило|статья)\\s+","");return text.matches("\\d+(?:\\.\\d+)+")?text:null;}
    public List<Match> find(String query,String category){return find(query,category,true);}
    public List<Match> find(String query,String category,boolean smart){
        String number=numberQuery(query);if(number!=null)return entries.stream().filter(e->e.rule.number().equals(number)&&(category==null||category.equals(e.rule.category()))).map(e->new Match(e.rule,10000)).toList();
        var words=terms(query);var intent=smart?topics(query,true):Set.<String>of();if(words.isEmpty()&&intent.isEmpty())return List.of();
        var result=new ArrayList<Match>();
        for(var entry:entries){
            if(category!=null&&!category.equals(entry.rule.category()))continue;
            var overlap=new HashSet<>(entry.subjectTopics.isEmpty()?entry.topics:entry.subjectTopics);overlap.retainAll(intent);
            if(!intent.isEmpty()&&overlap.isEmpty())continue;
            double score=score(entry,words,smart)+overlap.size()*4+(smart?coverage(words,entry.keywordTerms)*6:0);
            if(!intent.isEmpty()){int subjects=(entry.subjectTopics.isEmpty()?entry.topics:entry.subjectTopics).size();score*=.4+.6*overlap.size()/Math.max(1,subjects);}
            if(score>0)result.add(new Match(entry.rule,(int)Math.round(score*100)));
        }
        result.sort(Comparator.comparingInt(Match::score).reversed());return result.stream().limit(500).toList();
    }
    private double score(Entry entry,Set<String> query,boolean smart){
        double score=0;for(String term:query){
            String matched=entry.frequencies.containsKey(term)?term:null;
            if(matched==null&&smart&&term.length()>=4)matched=entry.frequencies.keySet().stream().filter(t->t.length()>=4&&(t.startsWith(term)||term.startsWith(t))).min(Comparator.comparingInt(t->Math.abs(t.length()-term.length()))).orElse(null);
            if(matched==null)continue;double tf=entry.frequencies.get(matched),df=documentFrequency.getOrDefault(matched,1);
            double idf=Math.log(1+(entries.size()-df+.5)/(df+.5));
            score+=idf*(tf*2.2)/(tf+1.2*(.25+.75*entry.length/averageLength))*(matched.equals(term)?1:.65);
        }return score;
    }
    public double[] features(String query,RuleBook.Rule rule,double cosine){
        var entry=byRule.get(rule);if(entry==null)throw new IllegalArgumentException("Rule outside index");
        var words=terms(query);var intent=topics(query,true);var matched=new HashSet<>(entry.topics);matched.retainAll(intent);
        double all=coverage(words,entry.frequencies.keySet()),title=coverage(words,entry.titleTerms),keywords=coverage(words,entry.keywordTerms),lexical=score(entry,words,true);
        int topicCount=intent.size(),docTopics=entry.topics.size();String normalized=normalize(query);
        boolean phrase=normalized.length()>=3&&entry.title.contains(normalized);
        boolean keywordPhrase=rule.keywords().stream().anyMatch(k->normalize(k).equals(normalized));
        return new double[]{1,Math.max(0,cosine),lexical/(lexical+6),all,title,keywords,topicCount==0?0:matched.size()/(double)topicCount,
            docTopics==0?0:matched.size()/(double)docTopics,topicCount==0?0:1-matched.size()/(double)topicCount,
            topicCount==0||docTopics==0?0:(docTopics-matched.size())/(double)docTopics,phrase?1:0,keywordPhrase?1:0,
            entry.titleTerms.isEmpty()?0:words.stream().filter(entry.titleTerms::contains).count()/(double)entry.titleTerms.size(),intent.isEmpty()?0:1};
    }
    private static double coverage(Set<String> query,Set<String> words){if(query.isEmpty())return 0;return query.stream().filter(q->words.contains(q)||q.length()>=4&&words.stream().anyMatch(w->w.length()>=4&&(w.startsWith(q)||q.startsWith(w)))).count()/(double)query.size();}
    private static boolean has(String text,String... parts){for(String part:parts)if(text.contains(part))return true;return false;}
    private static boolean word(String text,String... prefixes){for(String token:WORDS.split(text))for(String prefix:prefixes)if(token.startsWith(prefix))return true;return false;}
    private static boolean exactWord(String text,String... words){for(String token:WORDS.split(text))for(String word:words)if(token.equals(word))return true;return false;}
    private static Set<String> topics(String value,boolean query){
        String text=normalize(value);var result=new HashSet<String>();
        boolean cheat=has(text,"чит","килаур","киллаур","killaura","xray","иксрей","софт")||has(text,"нечестное по","запрещенное по","запрещенного по","запрещенным по")||has(text,"руд")&&has(text,"сквозь","через стен");
        if(cheat)result.add("cheats");
        if(cheat&&(word(text,"напар","тим","союз","команд","совместн")||has(text,"друг")&&has(text,"игра","читерит","с ним")))result.add("team");
        if(has(text,"фанп","фунп","funpay","коммерц","авито")||has(text,"прода","продав","покуп","торгов","монетизац","обмен","сделк")&&has(text,"реальн","настоящ","внеигров","неигров","рубл","рубли","деньг","денеж"))result.add("commerce");
        boolean account=has(text,"аккаунт","учетк","профил","логин","парол");
        if(account&&has(text,"переда","предостав","доступ","прода","продав","отдат","отда","подар","дать друг"))result.add("account_transfer");
        if(account&&has(text,"взлом","укра","украд","чуж"))result.add("account_hack");
        if(account&&has(text,"восстанов","забыл","забыт","утерян","утрат"))result.add("account_recovery");
        if(has(text,"дюп","dupe","дублир","удво","размнож"))result.add("dupes");
        if(has(text,"баг","недоработ","ошибк игры","ошибк сервера","ошибкой","эксплуатац"))result.add("bugs");
        if(word(text,"лаг","тормоз")||has(text,"нагруз","нагруж","обруш","падение сервера"))result.add("lag");
        if(has(text,"автомат","автокоп","лесоруб","макрос")||word(text,"бот")||has(text,"сама","сам ")&&has(text,"добыва","копа","ходит"))result.add("automation");
        if(has(text,"автокоп","лесоруб")||has(text,"сам","отойд")&&has(text,"копа","добыва","руду"))result.add("auto_mining");
        boolean actor=has(text,"админ","модер","персонал","команда проекта");
        if(actor&&has(text,"притвор","прикин","выда","взять ник"))result.add("impersonation");
        if(actor&&has(text,"обман","соврат","соврать","заблужден"))result.add("staff_lie");
        if(has(text,"подстрек","подговор","провоц","провокац","склон")||cheat&&has(text,"предлож","поставить друг"))result.add("incitement");
        if(has(text,"ютуб","видео","видеоролик"))result.add("creator");
        if(has(text,"родител","родствен","семь","близких","родных")||exactWord(text,"мать","матери","матерей","матерью","мама","маму","маме","мамы","мамой","папа","папу","папе","папы","папой"))result.add("family");
        if(has(text,"оскорб","обозва","обзыва","идиот","унижа","ругат","руган","материт"))result.add("insult");
        if(has(text,"попрош","выпраш"))result.add("begging");
        if(has(text,"флуд","спам","капс","большими букв","заглавн","одинаков","одно и то же"))result.add("spam");
        if(has(text,"реклам","продвижен")||has(text,"другой сервер","чужой проект","сторонн","посторонн")&&has(text,"приглаш","позвать","позв","ссылк","ресурс"))result.add("advertising");
        if(has(text,"докс","сват","swat","личные данн","личную информац","персональн","чужой телефон","личный телефон","айпи","домашний адрес","фото","телефон")||has(text,"адрес")&&has(text,"чуж","игрок","пользовател"))result.add("privacy");
        if(has(text,"голос","микрофон","фонк","музык","посторонние звук"))result.add("voice");
        if(has(text,"поддел","поддель","фотошоп","нарисовать")&&has(text,"скрин","доказ","жалоб"))result.add("evidence");
        if(has(text,"призн","сознал")&&has(text,"провер"))result.add("confession");
        if(has(text,"проверк","проверяющ"))result.add("inspection");
        if(has(text,"никнейм","псевдоним","игровой ник")||exactWord(text,"ник","ника","нику","ником","ники"))result.add("nickname");
        if(result.isEmpty()&&has(text,"чат","общени","сообщен"))result.add("chat");
        return Set.copyOf(result);
    }
    public static boolean matchesIntent(String query,RuleBook.Rule rule){var wanted=topics(query,true);return wanted.isEmpty()||topics(rule.title()+" "+rule.description()+" "+String.join(" ",rule.keywords()),false).containsAll(wanted);}
}
