package cn.dreampie.route.core;


import cn.dreampie.common.http.HttpRequest;
import cn.dreampie.common.http.HttpResponse;
import cn.dreampie.common.util.Joiner;
import cn.dreampie.common.util.ParamNamesScaner;
import cn.dreampie.log.Logger;
import cn.dreampie.route.interceptor.Interceptor;
import cn.dreampie.route.render.RenderFactory;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.dreampie.common.util.Checker.checkArgument;
import static cn.dreampie.common.util.Checker.checkNotNull;

/**
 * Created by ice on 14-12-19.
 */
public class Route {

  private static final Logger logger = Logger.getLogger(Route.class);

  public static final String DEFAULT_PATTERN = "([^\\/]+)";
  private final String httpMethod;
  private final String pathPattern;
  private final String stdPathPattern;

  private final Pattern pattern;
  private final List<String> pathParamNames;

  private final Class<? extends Resource> resourceClass;
  private final Method method;
  private final List<String> allParamNames;
  private final List<Class<?>> allParamTypes;

  private final Interceptor[] interceptors;

  public Route(Class<? extends Resource> resourceClass, String httpMethod, String pathPattern, Method method, Interceptor[] interceptors) {
    this.resourceClass = resourceClass;
    this.httpMethod = checkNotNull(httpMethod);
    this.pathPattern = checkNotNull(pathPattern);
    this.method = method;

    this.interceptors = interceptors;

    allParamNames = ParamNamesScaner.getParamNames(method);
    allParamTypes = Arrays.asList(method.getParameterTypes());


    PathPatternParser s = new PathPatternParser(pathPattern);
    s.parse();

    pattern = Pattern.compile(s.patternBuilder.toString());
    stdPathPattern = s.stdPathPatternBuilder.toString();
    pathParamNames = s.pathParamNames;

    logger.info("Route build " + httpMethod + " " + pathPattern + " -> " + pattern);
  }


  public RouteMatch match(HttpRequest request, HttpResponse response) {
    if (!this.httpMethod.equals(request.getHttpMethod())) {
      return null;
    }
    String restPath = request.getRestPath();

    String extension = "";
    if (restPath.contains(".")) {
      int index = restPath.lastIndexOf(".");
      extension = restPath.substring(index + 1);
      if (RenderFactory.contains(extension)) {
        restPath = restPath.substring(0, index);
      } else {
        extension = "";
      }
    }

    Matcher m = pattern.matcher(restPath);
    if (!m.matches()) {
      return null;
    }
    //pathParams
    Map<String, String> params = new HashMap<String, String>();
    for (int i = 0; i < m.groupCount() && i < pathParamNames.size(); i++) {
      params.put(pathParamNames.get(i), m.group(i + 1));
    }
    //otherParams
    Map<String, List<String>> otherParams = request.getQueryParams();

    if (logger.isInfoEnabled()) {
      //print route
      StringBuilder sb = new StringBuilder("\n\nMatch route ----------------- ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append(" ------------------------------");
      sb.append("\nResource    : ").append(resourceClass.getName()).append(".(").append(resourceClass.getSimpleName()).append(".java:1)");
      sb.append("\nMethod      : ").append(method.getName());
      sb.append("\nPathPattern : ").append(httpMethod).append(" ").append(pathPattern);
      //print pathParams
      sb.append("\nPathParas   : ");
      if (params.size() > 0) {
        for (String key : params.keySet()) {
          sb.append(key).append("=").append(params.get(key));
          sb.append("  ");
        }
      }
      //print otherParams
      sb.append("\nOtherParas  : ");
      if (otherParams != null) {
        List<String> values;
        for (String key : otherParams.keySet()) {
          values = otherParams.get(key);
          if (values.size() == 1) {
            sb.append(key).append("=").append(values.get(0));
          } else {
            sb.append(key).append("[]={");
            sb.append(Joiner.on(",").join(values));
            sb.append("}");
          }
          sb.append("  ");
        }
      }
      if (interceptors != null && interceptors.length > 0) {
        sb.append("\nInterceptor : ");
        for (int i = 0; i < interceptors.length; i++) {
          if (i > 0)
            sb.append("\n              ");
          Interceptor inter = interceptors[i];
          Class<? extends Interceptor> ic = inter.getClass();
          sb.append(ic.getName()).append(".(").append(ic.getSimpleName()).append(".java:1)");
        }
        sb.append("\n");
      }
      sb.append("--------------------------------------------------------------------------------\n");
      logger.info(sb.toString());
    }

    return new RouteMatch(pathPattern, restPath, extension, params, otherParams, request, response);
  }


  public String toString() {
    return httpMethod + " " + pathPattern + " " + Joiner.on(",").join(allParamNames);
  }

  public Class<? extends Resource> getResourceClass() {
    return resourceClass;
  }

  public Method getMethod() {
    return method;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public String getPattern() {
    return pattern.pattern();
  }

  public String getPathPattern() {
    return pathPattern;
  }

  public String getStdPathPattern() {
    return stdPathPattern;
  }

  public List<String> getPathParamNames() {
    return pathParamNames;
  }

  public List<String> getAllParamNames() {
    return allParamNames;
  }

  public List<Class<?>> getAllParamTypes() {
    return allParamTypes;
  }

  public Interceptor[] getInterceptors() {
    return interceptors;
  }

  // here comes the path pattern parsing logic
  // the code is pretty ugly with lot of cross dependencies, I tried to keep it performant, correct, and maintainable
  // not sure those goals are all achieved though

  private static final class PathPatternParser {
    final int length;
    final String pathPattern;
    int offset = 0;
    PathParserCharProcessor processor = regularCharPathParserCharProcessor;
    List<String> pathParamNames = new ArrayList<String>();
    StringBuilder patternBuilder = new StringBuilder();
    StringBuilder stdPathPatternBuilder = new StringBuilder();

    private PathPatternParser(String pathPattern) {
      this.length = pathPattern.length();
      this.pathPattern = pathPattern;
    }

    void parse() {
      while (offset < length) {
        int curChar = pathPattern.codePointAt(offset);

        processor.handle(curChar, this);

        offset += Character.charCount(curChar);
      }
      processor.end(this);
    }
  }

  private static interface PathParserCharProcessor {
    void handle(int curChar, PathPatternParser pathPatternParser);

    void end(PathPatternParser pathPatternParser);
  }

  private static final class CurlyBracesPathParamPathParserCharProcessor implements PathParserCharProcessor {
    private int openBr = 1;
    private boolean inRegexDef;
    private StringBuilder pathParamName = new StringBuilder();
    private StringBuilder pathParamRegex = new StringBuilder();


    public void handle(int curChar, PathPatternParser pathPatternParser) {
      if (curChar == '}') {
        openBr--;
        if (openBr == 0) {
          // found matching brace, end of path param

          if (pathParamName.length() == 0) {
            // it was a mere {}, can't be interpreted as a path param
            pathPatternParser.processor = regularCharPathParserCharProcessor;
            pathPatternParser.patternBuilder.append("{}");
            pathPatternParser.stdPathPatternBuilder.append("{}");
            return;
          }

          // only the opening paren
          checkArgument(pathParamRegex.length() != 1, "illegal path parameter definition '%s' at offset %d - custom regex must not be empty",
              pathPatternParser.pathPattern, pathPatternParser.offset);


          if (pathParamRegex.length() == 0) {
            // use default regex
            pathParamRegex.append(DEFAULT_PATTERN);
          } else {
            // close paren for matching group
            pathParamRegex.append(")");
          }

          pathPatternParser.processor = regularCharPathParserCharProcessor;
          pathPatternParser.patternBuilder.append(pathParamRegex);
          pathPatternParser.stdPathPatternBuilder.append("{").append(pathParamName).append("}");
          pathPatternParser.pathParamNames.add(pathParamName.toString());
          return;
        }
      } else if (curChar == '{') {
        openBr++;
      }

      if (inRegexDef) {
        pathParamRegex.appendCodePoint(curChar);
      } else {
        if (curChar == ':') {
          // we were in path name, the column marks the separator with the regex definition, we go in regex mode
          inRegexDef = true;
          pathParamRegex.append("(");
        } else {

          //only letters are authorized in path param name
          checkArgument(Character.isLetterOrDigit(curChar), "illegal path parameter definition '%s' at offset %d" +
                  " - only letters and digits are authorized in path param name",
              pathPatternParser.pathPattern, pathPatternParser.offset);

          pathParamName.appendCodePoint(curChar);
        }
      }
    }


    public void end(PathPatternParser pathPatternParser) {
    }
  }

  private static final class SimpleColumnBasedPathParamParserCharProcessor implements PathParserCharProcessor {
    private StringBuilder pathParamName = new StringBuilder();

    public void handle(int curChar, PathPatternParser pathPatternParser) {
      if (!Character.isLetterOrDigit(curChar)) {
        pathPatternParser.patternBuilder.append(DEFAULT_PATTERN);
        pathPatternParser.stdPathPatternBuilder.append("{").append(pathParamName).append("}");
        pathPatternParser.pathParamNames.add(pathParamName.toString());
        pathPatternParser.processor = regularCharPathParserCharProcessor;
        pathPatternParser.processor.handle(curChar, pathPatternParser);
      } else {
        pathParamName.appendCodePoint(curChar);
      }
    }


    public void end(PathPatternParser pathPatternParser) {
      pathPatternParser.patternBuilder.append(DEFAULT_PATTERN);
      pathPatternParser.stdPathPatternBuilder.append("{").append(pathParamName).append("}");
      pathPatternParser.pathParamNames.add(pathParamName.toString());
    }
  }

  private static final PathParserCharProcessor regularCharPathParserCharProcessor = new PathParserCharProcessor() {

    public void handle(int curChar, PathPatternParser pathPatternParser) {
      if (curChar == '{') {
        pathPatternParser.processor = new CurlyBracesPathParamPathParserCharProcessor();
      } else if (curChar == ':') {
        pathPatternParser.processor = new SimpleColumnBasedPathParamParserCharProcessor();
      } else {
        pathPatternParser.patternBuilder.appendCodePoint(curChar);
        pathPatternParser.stdPathPatternBuilder.appendCodePoint(curChar);
      }
    }


    public void end(PathPatternParser pathPatternParser) {
    }
  };
}
