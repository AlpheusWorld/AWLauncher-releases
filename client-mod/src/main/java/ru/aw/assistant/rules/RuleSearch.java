package ru.aw.assistant.rules;

import java.text.Normalizer;
import java.util.*;

public final class RuleSearch {
    public record Match(RuleBook.Rule rule, int score) {}
    private record Entry(RuleBook.Rule rule, String number, String title, String body, String keywords,
                         Set<String> titleTerms,Set<String> bodyTerms,Set<String> keywordTerms,Set<String> topics) {}
    private static final Set<String> STOP=Set.of("а","и","или","в","во","на","за","по","с","со","к","у","из","для","при","ли","что","как","если","это","я","мне","меня","можно","нельзя","будет","могу","правило","пункт");
    private static final String[] ENDINGS={"иями","ями","ами","ого","его","ому","ему","ыми","ими","ов","ев","ам","ям","ах","ях","ой","ый","ий","ая","яя","ую","юю","ое","ее","ые","ие","ем","ом","а","я","ы","и","у","ю","е","о"};
    private static final java.util.regex.Pattern WORDS=java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final java.util.regex.Pattern RUSSIAN=java.util.regex.Pattern.compile("[а-я]+");
    private final List<Entry> entries;
    public RuleSearch(RuleBook book) {
        entries = book.rules().stream().map(r -> {
            String title=normalize(r.title()),body=normalize(r.description()+" "+r.sanction()),keywords=normalize(String.join(" ",r.keywords()));
            return new Entry(r,normalize(r.number()),title,body,keywords,terms(title),terms(body),terms(keywords),topics(title+" "+normalize(r.description())+" "+keywords));
        }).toList();
    }
    public List<Match> find(String query, String category) {
        return find(query,category,true);
    }
    public List<Match> find(String query,String category,boolean smart) {
        String normalized = normalize(query);
        if (normalized.isBlank()) return List.of();
        var tokens=terms(normalized);
        var intent=smart?queryTopics(normalized):Set.<String>of();
        boolean numberQuery=normalized.matches("\\d+(?:\\.\\d+)+");
        if(tokens.isEmpty()&&intent.isEmpty()&&!numberQuery)return List.of();
        var matches = new ArrayList<Match>();
        for (var entry : entries) {
            if (category != null && !category.equals(entry.rule.category())) continue;
            if(numberQuery){if(entry.number.equals(normalized))matches.add(new Match(entry.rule,10000));continue;}
            int score = 0,found=0;
            for (String token : tokens) {
                int value = termScore(token,entry.titleTerms,smart)*8+termScore(token,entry.keywordTerms,smart)*6+termScore(token,entry.bodyTerms,smart);
                if(value>0){found++;score+=value;}
            }
            int concepts=0;
            for(String topic:intent)if(entry.topics.contains(topic)){concepts++;score+=80;}
            if(!intent.isEmpty()&&concepts!=intent.size())continue;
            if(intent.isEmpty()&&found<Math.max(1,(tokens.size()+1)/2))continue;
            if(score>0)matches.add(new Match(entry.rule,score));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed());
        return matches.stream().limit(500).toList();
    }
    private static int termScore(String query,Set<String> words,boolean smart){
        if(words.contains(query))return 10;
        if(!smart)return 0;
        for(String word:words)if(query.length()>=3&&word.length()>=3&&(word.startsWith(query)||query.startsWith(word)))return 5;
        return 0;
    }
    private static Set<String> terms(String text){
        var result=new LinkedHashSet<String>();
        for(String word:WORDS.split(normalize(text)))if(!word.isBlank()&&!STOP.contains(word))result.add(stem(word));
        return Set.copyOf(result);
    }
    private static String stem(String word){
        if(word.length()<4||!RUSSIAN.matcher(word).matches())return word;
        for(String ending:ENDINGS)if(word.endsWith(ending)&&word.length()-ending.length()>=3)return word.substring(0,word.length()-ending.length());
        return word;
    }
    private static Set<String> queryTopics(String text){
        var result=new HashSet<String>();
        String s=normalize(text).replace("-","");
        if(has(s,"фанп","фунп","funpay","коммерц","реальные деньги","реальную валюту")||has(s,"продат","продаж","продав","купит","покуп","обмен")&&has(s,"рубл","рубли","реал","деньг","донат","вне игр"))result.add("commerce");
        if(has(s,"читы","читов","читер","cheat","килаур","киллаур","killaura","xray","иксрей","софт")||terms(s).stream().anyMatch(t->t.equals("чит")))result.add("cheats");
        if(has(s,"дюп","dupe","дублирован"))result.add("dupes");
        if(has(s,"флуд","спам","caps","капс"))result.add("spam");
        if(has(s,"докс","сват","swat","личные данные","слив данных"))result.add("privacy");
        return Set.copyOf(result);
    }
    private static Set<String> topics(String text){
        var result=new HashSet<String>();
        if(has(text,"коммерц","funpay","фанп") || has(text,"продаж","продав","продат","покуп","торгов","сделк","монетизац","бартер")&&has(text,"реальн","настоящ","внеигров","неигров","реал","рубл"))result.add("commerce");
        if(has(text,"запрещенн")&&has(text,"програм","модификац","софт","по.","по ","автоматизац")||
            has(text,"чит")&&has(text,"использ","хранен","пользова","примен","преимуществ"))result.add("cheats");
        if(has(text,"дюп","дублирован"))result.add("dupes");
        if(has(text,"флуд","спам","капс"))result.add("spam");
        if(has(text,"докс","сват","личн")&&has(text,"данн","информац","докс","сват"))result.add("privacy");
        return Set.copyOf(result);
    }
    private static boolean has(String text,String... fragments){for(String fragment:fragments)if(text.contains(fragment))return true;return false;}
    public static boolean matchesIntent(String query,RuleBook.Rule rule){var intent=queryTopics(query);return intent.isEmpty()||topics(normalize(rule.title()+" "+rule.description()+" "+String.join(" ",rule.keywords()))).containsAll(intent);}
    public static String normalize(String text) { return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е').strip(); }
}
