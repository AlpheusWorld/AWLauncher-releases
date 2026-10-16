package ru.aw.assistant.rules;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Explicit offline feature export; never downloads models during ordinary tests. */
public final class SearchQualitySmoke {
    public static void main(String[] args) throws Exception {
        var report=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray().get(1).getAsJsonObject();
        var cases=report.getAsJsonArray("cases");var corpus=Path.of(args[1]);var books=new HashMap<String,RuleBook>();var indexes=new HashMap<String,RuleSearch>();
        var json=new Gson();
        for(var source:BuiltinRules.SOURCES){var file=corpus.resolve(source.id()+".json");var book=Files.exists(file)?RuleBook.read(file):BuiltinRules.bundled(source);book=BuiltinRules.withShortTitles(source,book);books.put(source.id(),book);indexes.put(source.id(),new RuleSearch(book));}
        var rows=new JsonArray();
        SemanticSearch engine=args.length>3?new SemanticSearch(Path.of(args[3])):null;if(engine!=null)engine.enabled(true);
        double maxError=0;int positives=0,negatives=0,top1=0,top3=0,rejected=0;
        for(var raw:cases){var item=raw.getAsJsonObject();String server=item.get("server").getAsString(),query=item.get("query").getAsString();
            var book=books.get(server);var rules=new HashMap<String,RuleBook.Rule>();book.rules().forEach(rule->rules.put(rule.number(),rule));var index=indexes.get(server);
            Map<RuleBook.Rule,Float> actual=engine==null?Map.of():engine.similarities(book,query,null);
            if(engine!=null&&item.get("split").getAsString().equals("holdout")){var ranked=SemanticSearch.rank(query,index,actual);var expected=json.fromJson(item.get("expected"),String[].class);var accepted=Set.of(expected);if(expected.length==0){negatives++;if(ranked.isEmpty())rejected++;}else{positives++;if(!ranked.isEmpty()&&accepted.contains(ranked.getFirst().rule().number()))top1++;if(ranked.stream().limit(3).anyMatch(m->accepted.contains(m.rule().number())))top3++;}}
            var candidates=new JsonArray();
            for(var rawScore:item.getAsJsonArray("ranked")){var score=rawScore.getAsJsonArray();String number=score.get(0).getAsString();var candidate=new JsonObject();candidate.addProperty("number",number);candidate.add("features",json.toJsonTree(index.features(query,rules.get(number),engine==null?score.get(1).getAsDouble():actual.get(rules.get(number)))));candidates.add(candidate);if(engine!=null)maxError=Math.max(maxError,Math.abs(actual.get(rules.get(number))-score.get(1).getAsDouble()));}
            var row=new JsonObject();row.addProperty("server",server);row.addProperty("query",query);row.add("expected",item.get("expected"));row.add("split",item.get("split"));row.add("candidates",candidates);rows.add(row);
        }
        var output=new JsonObject();output.add("features",json.toJsonTree(RuleSearch.FEATURE_NAMES));output.add("cases",rows);Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(output));System.out.println("Exported production relevance features for "+rows.size()+" queries");
        if(engine!=null){engine.close();System.out.println("Native Java encoder: top1="+top1+"/"+positives+", top3="+top3+"/"+positives+", rejected="+rejected+"/"+negatives+", maximum Python cosine difference="+maxError);if(maxError>.001)throw new AssertionError("Tokenizer/encoder mismatch: "+maxError);if(top1<positives*.9||rejected<negatives*.9)throw new AssertionError("Relevance regression on development control queries");}
    }
}
