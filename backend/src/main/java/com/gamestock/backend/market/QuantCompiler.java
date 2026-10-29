package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;
import static com.gamestock.backend.market.MarketModels.ChartCandle;

/** snScript v1 and BlockQuant v1 compile to the same bounded, non-executable AST. */
public final class QuantCompiler {
    public record Expr(String op,Double value,String name,Integer period,List<Expr> args) {}
    public record Binding(String name,Expr expression) {}
    public record Rule(String side,int quantity,Expr condition) {}
    public record Program(int version,List<Binding> bindings,List<Binding> plots,List<Rule> rules) {}
    public record Diagnostic(int line,int column,String message) {}
    public record Compilation(boolean valid,Program program,List<Diagnostic> diagnostics) {}
    static final Set<String> FIELDS=Set.of("open","high","low","close","volume");
    static final Set<String> FUNCTIONS=Set.of("sma","ema","rsi","stddev","atr","crossover","crossunder","abs");
    static final Set<String> BINARY=Set.of("+","-","*","/",">",">=","<","<=","==","!=","and","or","crossover","crossunder");
    static Expr expr(String op,Expr... args) {return new Expr(op,null,null,null,List.of(args));}
    static Expr constant(double value) {return new Expr("number",value,null,null,List.of());}
    static Expr reference(String name) {return new Expr("ref",null,name,null,List.of());}
    public static Compilation compile(String language,String source,JsonNode blocks) {
        try {
            Program program;
            if("SNSCRIPT".equals(language)) program=new Parser(source).parse();
            else if("BLOCKS".equals(language)) program=blocks(blocks);
            else throw new IllegalArgumentException("language는 SNSCRIPT 또는 BLOCKS입니다.");
            validate(program);return new Compilation(true,program,List.of());
        } catch(Syntax e) {return new Compilation(false,null,List.of(new Diagnostic(e.line,e.column,e.getMessage())));}
        catch(IllegalArgumentException e) {return new Compilation(false,null,List.of(new Diagnostic(1,1,e.getMessage())));}
    }
    private static Program blocks(JsonNode root) {
        if(root==null || !root.isObject() || !root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt() || root.path("version").asInt()!=1 || root.toString().length()>32768)
            throw new IllegalArgumentException("BlockQuant version 1 객체(최대 32KB)가 필요합니다.");
        List<Binding> bindings=new ArrayList<>(),plots=new ArrayList<>();List<Rule> rules=new ArrayList<>();
        for(JsonNode b:array(root,"bindings",32)) bindings.add(new Binding(text(b,"name"),node(b.get("expression"),0)));
        for(JsonNode p:array(root,"plots",8)) plots.add(new Binding(text(p,"name"),node(p.get("expression"),0)));
        for(JsonNode r:array(root,"rules",8)) {
            if(!r.path("quantity").isIntegralNumber() || !r.path("quantity").canConvertToInt()) throw new IllegalArgumentException("quantity는 정수입니다.");
            rules.add(new Rule(text(r,"side"),r.get("quantity").intValue(),node(r.get("condition"),0)));
        }
        return new Program(1,List.copyOf(bindings),List.copyOf(plots),List.copyOf(rules));
    }
    private static JsonNode array(JsonNode n,String key,int max) {
        JsonNode a=n.get(key);if(a==null || !a.isArray() || a.size()>max) throw new IllegalArgumentException(key+" 배열 한도를 확인해 주세요.");return a;
    }
    private static String text(JsonNode n,String key) {
        if(!n.path(key).isTextual()) throw new IllegalArgumentException(key+" 문자열이 필요합니다.");return n.get(key).textValue();
    }
    private static Expr node(JsonNode n,int depth) {
        if(n==null || !n.isObject() || depth>24) throw new IllegalArgumentException("블록 중첩은 최대 24단계입니다.");
        String op=text(n,"op");
        if(op.equals("number")) {
            if(!n.path("value").isNumber()) throw new IllegalArgumentException("숫자 value가 필요합니다.");return constant(n.get("value").doubleValue());
        }
        if(op.equals("ref")) return reference(text(n,"name"));
        List<Expr> args=new ArrayList<>();for(JsonNode a:array(n,"args",2)) args.add(node(a,depth+1));
        Integer period=null;
        if(n.has("period")) {
            if(!n.get("period").isIntegralNumber() || !n.get("period").canConvertToInt()) throw new IllegalArgumentException("period는 정수입니다.");
            period=n.get("period").intValue();
        }
        return new Expr(op,null,null,period,List.copyOf(args));
    }
    static void validate(Program p) {
        if(p==null || p.version()!=1 || p.bindings().size()>32 || p.plots().size()>8 || p.rules().size()>8)
            throw new IllegalArgumentException("프로그램 한도를 초과했습니다.");
        Set<String> names=new HashSet<>(FIELDS),plotNames=new HashSet<>();int[] nodes={0};
        for(Binding b:p.bindings()) {
            identifier(b.name());if(names.contains(b.name())) throw new IllegalArgumentException("중복 변수: "+b.name());
            validateExpr(b.expression(),names,0,nodes);names.add(b.name());
        }
        for(Binding b:p.plots()) {
            identifier(b.name());if(!plotNames.add(b.name())) throw new IllegalArgumentException("중복 plot: "+b.name());
            validateExpr(b.expression(),names,0,nodes);
        }
        for(Rule r:p.rules()) {
            if(!Set.of("BUY","SELL").contains(r.side()) || r.quantity()<1 || r.quantity()>1000)
                throw new IllegalArgumentException("주문은 BUY/SELL, 수량은 1~1000 정수입니다.");
            validateExpr(r.condition(),names,0,nodes);
        }
    }
    private static void identifier(String name) {
        if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,31}") || Set.of("let","plot","buy","sell","when","quantity","and","or","not","true","false").contains(name))
            throw new IllegalArgumentException("잘못된 식별자: "+name);
    }
    private static void validateExpr(Expr e,Set<String> names,int depth,int[] nodes) {
        if(e==null || depth>24 || ++nodes[0]>256) throw new IllegalArgumentException("식은 최대 256개 노드, 24단계입니다.");
        String op=e.op();int count=e.args()==null?0:e.args().size();
        if("number".equals(op)) {
            if(e.value()==null || !Double.isFinite(e.value()) || Math.abs(e.value())>1e12 || count!=0) throw new IllegalArgumentException("숫자 범위를 확인해 주세요.");return;
        }
        if("ref".equals(op)) {
            if(!names.contains(e.name()) || count!=0) throw new IllegalArgumentException("정의되지 않은 변수: "+e.name());return;
        }
        int required=BINARY.contains(op)?2:Set.of("sma","ema","rsi","stddev","abs","not","neg").contains(op)?1:"atr".equals(op)?0:-1;
        if(required<0 || count!=required) throw new IllegalArgumentException("지원하지 않는 연산 또는 인자 수: "+op);
        if(Set.of("sma","ema","rsi","stddev","atr").contains(op)) {
            if(e.period()==null) throw new IllegalArgumentException("period가 필요합니다.");TechnicalIndicators.period(e.period());
        } else if(e.period()!=null) throw new IllegalArgumentException("period를 받지 않는 연산입니다.");
        if(e.args()!=null) for(Expr a:e.args()) validateExpr(a,names,depth+1,nodes);
    }
    public record Evaluation(Map<String,List<Double>> plots,List<double[]> signals) {}
    static Evaluation evaluate(Program p,List<ChartCandle> bars) {
        validate(p);if(bars.size()>2000) throw new IllegalArgumentException("캔들은 최대 2000개입니다.");
        Map<String,double[]> env=new HashMap<>();for(String field:FIELDS) env.put(field,ResearchChartService.field(bars,field));
        Map<Expr,double[]> cache=new HashMap<>();
        for(Binding b:p.bindings()) env.put(b.name(),eval(b.expression(),env,cache,bars.size()));
        Map<String,List<Double>> plots=new LinkedHashMap<>();
        for(Binding b:p.plots()) plots.put(b.name(),ResearchChartService.boxed(eval(b.expression(),env,cache,bars.size())));
        List<double[]> signals=new ArrayList<>();for(Rule r:p.rules()) signals.add(eval(r.condition(),env,cache,bars.size()));
        return new Evaluation(plots,signals);
    }
    private static double[] eval(Expr e,Map<String,double[]> env,Map<Expr,double[]> cache,int n) {
        double[] existing=cache.get(e);if(existing!=null)return existing;
        double[] out;
        if(e.op().equals("ref")) out=env.get(e.name());
        else if(e.op().equals("number")) {out=new double[n];Arrays.fill(out,e.value());}
        else {
            double[] a=e.args().isEmpty()?null:eval(e.args().get(0),env,cache,n);
            double[] b=e.args().size()<2?null:eval(e.args().get(1),env,cache,n);
            out=switch(e.op()) {
                case "sma"->TechnicalIndicators.sma(a,e.period());case "ema"->TechnicalIndicators.ema(a,e.period());
                case "rsi"->TechnicalIndicators.rsi(a,e.period());case "stddev"->TechnicalIndicators.stddev(a,e.period());
                case "atr"->TechnicalIndicators.atr(env.get("high"),env.get("low"),env.get("close"),e.period());
                default-> {
                    double[] result=TechnicalIndicators.empty(n);
                    for(int i=0;i<n;i++) {
                        if(!Double.isFinite(a[i]) || (b!=null && !Double.isFinite(b[i]))) continue;
                        double x=a[i],y=b==null?0:b[i];
                        result[i]=switch(e.op()) {
                            case "+"->x+y;case "-"->x-y;case "*"->x*y;case "/"->y==0?Double.NaN:x/y;
                            case "neg"->-x;case "abs"->Math.abs(x);case "not"->truth(x)?0:1;
                            case ">"->x>y?1:0;case ">="->x>=y?1:0;case "<"->x<y?1:0;case "<="->x<=y?1:0;
                            case "=="->x==y?1:0;case "!="->x!=y?1:0;
                            case "and"->truth(x)&&truth(y)?1:0;case "or"->truth(x)||truth(y)?1:0;
                            case "crossover"->i>0 && Double.isFinite(a[i-1]) && Double.isFinite(b[i-1]) && a[i-1]<=b[i-1] && x>y?1:0;
                            case "crossunder"->i>0 && Double.isFinite(a[i-1]) && Double.isFinite(b[i-1]) && a[i-1]>=b[i-1] && x<y?1:0;
                            default->throw new IllegalArgumentException("지원하지 않는 연산입니다.");
                        };
                        if(!Double.isFinite(result[i]))result[i]=Double.NaN;
                    }yield result;
                }
            };
        }
        cache.put(e,out);return out;
    }
    static boolean truth(double n) {return Double.isFinite(n) && n!=0;}

    private static final class Syntax extends IllegalArgumentException {
        final int line,column;Syntax(int line,int column,String message) {super(message);this.line=line;this.column=column;}
    }
    private record Token(String text,int line,int column) {}
    private static final class Parser {
        private static final Pattern TOKEN=Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*|(?:[0-9]+(?:\\.[0-9]+)?)|>=|<=|==|!=|[+*/<>=(),;\\-]");
        final List<Token> tokens=new ArrayList<>();int pos,depth;
        Parser(String source) {
            if(source==null || source.length()>32768) throw new IllegalArgumentException("소스는 최대 32KB입니다.");
            String[] lines=source.split("\\R",-1);
            for(int i=0;i<lines.length;i++) {
                String line=lines[i];int comment=line.indexOf("//");if(comment>=0)line=line.substring(0,comment);
                int at=0;while(at<line.length()) {
                    if(Character.isWhitespace(line.charAt(at))) {at++;continue;}
                    Matcher m=TOKEN.matcher(line);m.region(at,line.length());
                    if(!m.lookingAt()) throw new Syntax(i+1,at+1,"지원하지 않는 문자입니다.");
                    tokens.add(new Token(m.group(),i+1,at+1));at=m.end();
                    if(tokens.size()>2048) throw new Syntax(i+1,at+1,"토큰 한도를 초과했습니다.");
                }
                tokens.add(new Token(";",i+1,line.length()+1));
            }
            tokens.add(new Token("<EOF>",lines.length+1,1));
        }
        Token token() {return tokens.get(pos);}
        boolean take(String text) {if(token().text().equals(text)){pos++;return true;}return false;}
        Syntax error(String text) {return new Syntax(token().line(),token().column(),text);}
        void expect(String text) {if(!take(text))throw error("'"+text+"'가 필요합니다.");}
        String name() {String name=token().text();identifier(name);pos++;return name;}
        Program parse() {
            List<Binding> bindings=new ArrayList<>(),plots=new ArrayList<>();List<Rule> rules=new ArrayList<>();
            while(!token().text().equals("<EOF>")) {
                if(take(";")) continue;
                if(take("let")) {String name=name();expect("=");bindings.add(new Binding(name,expression(0)));}
                else if(take("plot")) {String name=name();expect("=");plots.add(new Binding(name,expression(0)));}
                else {
                    String side=token().text();if(!take("buy") && !take("sell")) throw error("let, plot, buy, sell 문장이 필요합니다.");
                    expect("when");Expr condition=expression(0);expect("quantity");
                    String quantity=token().text();if(!quantity.matches("[0-9]{1,4}"))throw error("수량은 정수 상수입니다.");pos++;
                    rules.add(new Rule(side.toUpperCase(Locale.ROOT),Integer.parseInt(quantity),condition));
                }
                expect(";");
            }
            return new Program(1,List.copyOf(bindings),List.copyOf(plots),List.copyOf(rules));
        }
        int precedence(String op) {return switch(op){case "or"->1;case "and"->2;case ">",">=","<","<=","==","!="->3;case "+","-"->4;case "*","/"->5;default->-1;};}
        Expr expression(int minimum) {
            if(++depth>24)throw error("식 중첩은 최대 24단계입니다.");
            Expr left;
            if(take("not"))left=expr("not",expression(6));
            else if(take("-"))left=expr("neg",expression(6));
            else if(take("(")) {left=expression(0);expect(")");}
            else if(take("true"))left=constant(1);
            else if(take("false"))left=constant(0);
            else if(token().text().matches("[0-9]+(?:\\.[0-9]+)?")) {left=constant(Double.parseDouble(token().text()));pos++;}
            else {
                String name=token().text();pos++;
                if(take("(")) {
                    String fn=name.startsWith("ta.")?name.substring(3):name;
                    if(!FUNCTIONS.contains(fn))throw error("지원하지 않는 함수: "+name);
                    List<Expr> args=new ArrayList<>();Integer period=null;
                    if(!fn.equals("atr"))args.add(expression(0));
                    if(Set.of("sma","ema","rsi","stddev","atr").contains(fn)) {
                        if(!fn.equals("atr"))expect(",");
                        if(!token().text().matches("[0-9]{1,3}"))throw error("period는 정수 상수입니다.");
                        period=Integer.parseInt(token().text());pos++;
                    } else if(fn.equals("crossover") || fn.equals("crossunder")) {expect(",");args.add(expression(0));}
                    expect(")");left=new Expr(fn,null,null,period,List.copyOf(args));
                } else {identifier(name);left=reference(name);}
            }
            while(precedence(token().text())>=minimum) {
                String op=token().text();pos++;left=expr(op,left,expression(precedence(op)+1));
            }
            depth--;return left;
        }
    }
}
