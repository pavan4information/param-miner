package burp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.google.gson.*;
import org.apache.commons.lang3.StringEscapeUtils;

import org.apache.commons.lang3.StringUtils;

import static burp.Keysmith.getHtmlKeys;
import static burp.Keysmith.getWords;
import static java.util.concurrent.ConcurrentHashMap.newKeySet;

public class BurpExtender implements IBurpExtender {
    private static final String name = "Backslash Powered Scanner";
    private static final String version = "0.91";

    @Override
    public void registerExtenderCallbacks(final IBurpExtenderCallbacks callbacks) {

        new Utilities(callbacks);
        BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        ThreadPoolExecutor taskEngine = new ThreadPoolExecutor(10, 10, 10, TimeUnit.MINUTES, tasks);
        callbacks.setExtensionName(name);

        try {
            StringUtils.isNumeric("1");
        } catch (java.lang.NoClassDefFoundError e) {
            Utilities.out("Failed to import the Apache Commons Lang library. You can get it from http://commons.apache.org/proper/commons-lang/");
            throw new NoClassDefFoundError();
        }

        try {
            callbacks.getHelpers().analyzeResponseVariations();
        } catch (java.lang.NoSuchMethodError e) {
            Utilities.out("This extension requires Burp Suite Pro 1.7.10 or later");
            throw new NoSuchMethodError();
        }

        FastScan scan = new FastScan(callbacks);
        callbacks.registerScannerCheck(scan);
        callbacks.registerExtensionStateListener(scan);

        ParamGrabber paramGrabber = new ParamGrabber();
        callbacks.registerContextMenuFactory(new OfferParamGuess(callbacks, paramGrabber, taskEngine));
        callbacks.registerIntruderPayloadGeneratorFactory(new ParamSpammerFactory(paramGrabber));
        callbacks.registerScannerCheck(paramGrabber);
        callbacks.registerHttpListener(new Substituter());

        Utilities.out("Loaded " + name + " v" + version);
        Utilities.out("Debug mode: " + Utilities.DEBUG);
        Utilities.out("Thorough mode: " + Utilities.THOROUGH_MODE);
        Utilities.out("Input transformation detection: " + Utilities.TRANSFORMATION_SCAN);
        Utilities.out("Suspicious input handling detection: " + Utilities.DIFFING_SCAN);
        Utilities.out("    TRY_SYNTAX_ATTACKS "+Utilities.TRY_SYNTAX_ATTACKS);
        Utilities.out("    TRY_VALUE_PRESERVING_ATTACKS "+Utilities.TRY_VALUE_PRESERVING_ATTACKS);
        Utilities.out("    TRY_EXPERIMENTAL_CONCAT_ATTACKS "+Utilities.TRY_EXPERIMENTAL_CONCAT_ATTACKS);
        Utilities.out("    TRY_HPP "+Utilities.TRY_HPP);
        Utilities.out("    TRY_HPP_FOLLOWUP "+Utilities.TRY_HPP_FOLLOWUP);
        Utilities.out("    TRY_MAGIC_VALUE_ATTACKS "+Utilities.TRY_MAGIC_VALUE_ATTACKS);

    }


}

class Substituter implements IHttpListener {

    public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo) {
        if (messageIsRequest) {
            byte[] placeHolder = Utilities.helpers.stringToBytes("$randomplz");
            if (Utilities.countMatches(messageInfo.getRequest(), placeHolder) > 0) {
                messageInfo.setRequest(
                        Utilities.fixContentLength(Utilities.replace(messageInfo.getRequest(), placeHolder, Utilities.helpers.stringToBytes(Utilities.generateCanary())))
                );
            }
        }
    }
}

class ParamSpammerFactory implements IIntruderPayloadGeneratorFactory {

    ParamGrabber grabber;

    public ParamSpammerFactory(ParamGrabber grabber) {
        this.grabber = grabber;
    }

    @Override
    public String getGeneratorName() {
        return "Observed Parameter Spammer";
    }

    @Override
    public IIntruderPayloadGenerator createNewInstance(IIntruderAttack attack) {
        attack.getRequestTemplate();
        byte[] baseLine = attack.getRequestTemplate();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        //byte foo = (byte) 0xa7;
        for(byte b: baseLine) {
            if (b != (byte) 0xa7) { // §
                outputStream.write(b);
            }
        }
        baseLine = Utilities.fixContentLength(outputStream.toByteArray());
        IHttpRequestResponse req = Utilities.callbacks.makeHttpRequest(attack.getHttpService(), baseLine);
        ArrayList<String> params = ParamGuesser.calculatePayloads(req, IParameter.PARAM_BODY, grabber);
        return new ParamSpammer(params);
    }
}

class ParamSpammer implements IIntruderPayloadGenerator {

    private ArrayList<String> params;
    private int index = 0;

    public ParamSpammer(ArrayList<String> params) {
        this.params = params;
    }

    @Override
    public boolean hasMorePayloads() {
        return (params.size() > index+1);
    }

    @Override
    public byte[] getNextPayload(byte[] baseValue) {
        return Utilities.helpers.stringToBytes(params.get(index++));
    }

    @Override
    public void reset() {
        index = 0;
    }
}


class ParamGrabber implements  IScannerCheck {

    public Set<IHttpRequestResponse> getSavedJson() {
        return savedJson;
    }

    Set<IHttpRequestResponse> savedJson;
    HashSet<ArrayList<String>> done;

    public Set<String> getSavedGET() {
        return savedGET;
    }

    Set<String> savedGET;

    public Set<String> getSavedWords() {
        return savedWords;
    }

    Set<String> savedWords;

    ParamGrabber() {
        savedJson = ConcurrentHashMap.newKeySet();
        //savedJson = ConcurrentHashMap.newKeySet();//new HashSet<>();
        done = new HashSet<>();
        savedWords = ConcurrentHashMap.newKeySet();
        savedGET = ConcurrentHashMap.newKeySet();
    }

    @Override
    public List<IScanIssue> doActiveScan(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {
        return new ArrayList<>();
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        saveParams(baseRequestResponse);
        return new ArrayList<>();
    }

    public void saveParams(IHttpRequestResponse baseRequestResponse) {
        // todo also use observed requests
        String body = Utilities.getBody(baseRequestResponse.getResponse());
        if (!body.equals("")) {
            savedWords.addAll(getWords(body));
            savedGET.addAll(getHtmlKeys(body));
            try {
                JsonParser parser = new JsonParser();
                JsonElement json = parser.parse(body);
                ArrayList<String> keys = Keysmith.getJsonKeys(json, new HashMap<>());
                if (!done.contains(keys)) {
                    //Utilities.out("Importing observed data...");
                    done.add(keys);
                    savedJson.add(Utilities.callbacks.saveBuffersToTempFiles(baseRequestResponse));
                }
            } catch (JsonParseException e) {

            }
        }
    }

    @Override
    public int consolidateDuplicateIssues(IScanIssue existingIssue, IScanIssue newIssue) {
        if (existingIssue.getIssueName().equals(newIssue.getIssueName()) && existingIssue.getIssueDetail().equals(newIssue.getIssueDetail()))
            return -1;
        else return 0;
    }
}

class FastScan implements IScannerCheck, IExtensionStateListener {
    private TransformationScan transformationScan;
    private DiffingScan diffingScan;
    private IExtensionHelpers helpers;
    private IBurpExtenderCallbacks callbacks;

    FastScan(final IBurpExtenderCallbacks callbacks) {
        transformationScan = new TransformationScan(callbacks);
        diffingScan = new DiffingScan();
        this.callbacks = callbacks;
        helpers = callbacks.getHelpers();
    }

    public void extensionUnloaded() {
        Utilities.out("Unloading extension...");
        Utilities.unloaded.set(true);
    }

    private IParameter getParameterFromInsertionPoint(IScannerInsertionPoint insertionPoint, byte[] request) {
        IParameter baseParam = null;
        int basePayloadStart = insertionPoint.getPayloadOffsets("x".getBytes())[0];
        List<IParameter> params = helpers.analyzeRequest(request).getParameters();
        for (IParameter param : params) {
            if (param.getValueStart() == basePayloadStart && insertionPoint.getBaseValue().equals(param.getValue())) {
                baseParam = param;
                break;
            }
        }
        return baseParam;
    }

    public List<IScanIssue> doActiveScan(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {

        ArrayList<IScanIssue> issues = new ArrayList<>();
        if(!(Utilities.TRANSFORMATION_SCAN || Utilities.DIFFING_SCAN)) {
            Utilities.out("Aborting scan - all scanner checks disabled");
            return issues;
        }

        // make a custom insertion point to avoid burp excessively URL-encoding payloads
        IParameter baseParam = getParameterFromInsertionPoint(insertionPoint, baseRequestResponse.getRequest());
        if (baseParam != null && (baseParam.getType() == IParameter.PARAM_BODY || baseParam.getType() == IParameter.PARAM_URL)) {
            insertionPoint = new ParamInsertionPoint(baseRequestResponse.getRequest(), baseParam.getName(), baseParam.getValue(), baseParam.getType());
        }

        if (Utilities.TRANSFORMATION_SCAN) {
            issues.add(transformationScan.findTransformationIssues(baseRequestResponse, insertionPoint));
        }

        if (Utilities.DIFFING_SCAN) {
            issues.add(diffingScan.findReflectionIssues(baseRequestResponse, insertionPoint));
        }

        if (baseParam != null && (baseParam.getType() == IParameter.PARAM_BODY || baseParam.getType() == IParameter.PARAM_URL) && Utilities.getExtension(baseRequestResponse.getRequest()).equals(".php")) {
            String param_name = baseParam.getName() + "[]";
            byte[] newReq = helpers.removeParameter(baseRequestResponse.getRequest(), baseParam);
            IParameter newParam = helpers.buildParameter(param_name, baseParam.getValue(), baseParam.getType());
            newReq = helpers.addParameter(newReq, helpers.buildParameter(param_name, "", baseParam.getType()));
            newReq = helpers.addParameter(newReq, newParam);

            IScannerInsertionPoint arrayInsertionPoint = new ParamInsertionPoint(newReq, param_name, newParam.getValue(), newParam.getType());
            IHttpRequestResponse newBase = callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), arrayInsertionPoint.buildRequest(newParam.getValue().getBytes()));

            if (Utilities.TRANSFORMATION_SCAN) {
                issues.add(transformationScan.findTransformationIssues(newBase, arrayInsertionPoint));
            }

            if (Utilities.DIFFING_SCAN) {
                issues.add(diffingScan.findReflectionIssues(newBase, arrayInsertionPoint));
            }
        }

        return issues
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        return new ArrayList<>();

    }

    @Override
    public int consolidateDuplicateIssues(IScanIssue existingIssue, IScanIssue newIssue) {
        if (existingIssue.getIssueName().equals(newIssue.getIssueName()) && existingIssue.getIssueDetail().equals(newIssue.getIssueDetail()))
            return -1;
        else return 0;
    }
}


class Fuzzable extends CustomScanIssue {
    private final static String DETAIL = "The application reacts to inputs in a way that suggests it might be vulnerable to some kind of server-side code injection. The probes are listed below in chronological order, with evidence. Response attributes that only stay consistent in one probe-set are italicised, with the variable attribute starred.";
    private final static String REMEDIATION = "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. Try to determine the root cause of the observed behaviour." +
            "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results. ";

    Fuzzable(IHttpRequestResponse[] requests, URL url, String title, String detail, boolean reliable, String severity) {
        super(requests[0].getHttpService(), url, requests, title, DETAIL + detail, severity, calculateConfidence(reliable), REMEDIATION);
    }

    private static String calculateConfidence(boolean reliable) {
        String confidence = "Tentative";
        if (reliable) {
            confidence = "Firm";
        }
        return confidence;
    }

}

class InputTransformation extends CustomScanIssue {
    private final static String NAME = "Suspicious input transformation";
    private final static String DETAIL = "The application transforms input in a way that suggests it might be vulnerable to some kind of server-side code injection";
    private final static String REMEDIATION =
            "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. " +
                    "Try to determine the root cause of the observed input transformations. " +
                    "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results.";
    private final static String CONFIDENCE = "Tentative";

    InputTransformation(ArrayList<String> interesting, ArrayList<String> boring, IHttpRequestResponse base, URL url, String paramName) {
        super(base.getHttpService(), url, new IHttpRequestResponse[]{base}, NAME, generateDetail(interesting, boring, paramName), generateSeverity(interesting), CONFIDENCE, REMEDIATION);
    }

    private static String generateSeverity(ArrayList<String> interesting) {
        String severity = "High";
        if (interesting.size() == 1 && interesting.contains("\\0 => \0")) {
            severity = "Information";
        }
        return severity;
    }

    private static String generateDetail(ArrayList<String> interesting, ArrayList<String> boring, String paramName) {
        String details = DETAIL + "<br/><br/>Affected parameter:<code>" + StringEscapeUtils.escapeHtml4(paramName) + "</code><br/><br/>";
        details += "<p>Interesting transformations:</p><ul> ";
        for (String transform : interesting) {
            details += "<li><b><code style='font-size: 125%;'>" + StringEscapeUtils.escapeHtml4(transform) + "</code></b></li>";
        }
        details += "</ul><p>Boring transformations:</p><ul>";
        for (String transform : boring) {
            details += "<li><b><code>" + StringEscapeUtils.escapeHtml4(transform) + "</code></b></li>";
        }
        details += "</ul>";
        return details;
    }
}

class CustomScanIssue implements IScanIssue {
    private IHttpService httpService;
    private URL url;
    private IHttpRequestResponse[] httpMessages;
    private String name;
    private String detail;
    private String severity;
    private String confidence;
    private String remediation;

    CustomScanIssue(
            IHttpService httpService,
            URL url,
            IHttpRequestResponse[] httpMessages,
            String name,
            String detail,
            String severity,
            String confidence,
            String remediation) {
        this.name = name;
        this.detail = detail;
        this.severity = severity;
        this.httpService = httpService;
        this.url = url;
        this.httpMessages = httpMessages;
        this.confidence = confidence;
        this.remediation = remediation;
    }

    CustomScanIssue(
            IHttpService httpService,
            URL url,
            IHttpRequestResponse httpMessages,
            String name,
            String detail,
            String severity,
            String confidence,
            String remediation) {
        this.name = name;
        this.detail = detail;
        this.severity = severity;
        this.httpService = httpService;
        this.url = url;
        this.httpMessages = new IHttpRequestResponse[1];
        this.httpMessages[0] = httpMessages;

        this.confidence = confidence;
        this.remediation = remediation;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public String getIssueName() {
        return name;
    }

    @Override
    public int getIssueType() {
        return 0;
    }

    @Override
    public String getSeverity() {
        return severity;
    }

    @Override
    public String getConfidence() {
        return confidence;
    }

    @Override
    public String getIssueBackground() {
        return null;
    }

    @Override
    public String getRemediationBackground() {
        return null;
    }

    @Override
    public String getIssueDetail() {
        return detail;
    }

    @Override
    public String getRemediationDetail() {
        return remediation;
    }

    @Override
    public IHttpRequestResponse[] getHttpMessages() {
        return httpMessages;
    }

    @Override
    public IHttpService getHttpService() {
        return httpService;
    }

    public String getHost() {
        return null;
    }

    public int getPort() {
        return 0;
    }

    public String getProtocol() {
        return null;
    }
}


class RequestWithOffsets {
    private byte[] request;
    private int[] offsets;

    public RequestWithOffsets(byte[] request, int[] offsets) {
        this.request = request;
        this.offsets = offsets;
    }
}

class ParamInsertionPoint implements IScannerInsertionPoint {
    byte[] request;
    String name;
    String value;
    byte type;

    ParamInsertionPoint(byte[] request, String name, String value, byte type) {
        this.request = request;
        this.name = name;
        this.value = value;
        this.type = type;
    }

    String calculateValue(String unparsed) {
        return unparsed;
    }

    @Override
    public String getInsertionPointName() {
        return name;
    }

    @Override
    public String getBaseValue() {
        return value;
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(Utilities.helpers.bytesToString(payload)), type);
        return Utilities.helpers.updateParameter(request, newParam);
    }

    @Override
    public int[] getPayloadOffsets(byte[] payload) {
        //IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(Utilities.helpers.bytesToString(payload)), type);
        return new int[]{0, 0};
        //return new int[]{newParam.getValueStart(), newParam.getValueEnd()};
    }

    @Override
    public byte getInsertionPointType() {
        return type;
        //return IScannerInsertionPoint.INS_PARAM_BODY;
        // return IScannerInsertionPoint.INS_EXTENSION_PROVIDED;
    }
}

class ParamNameInsertionPoint extends ParamInsertionPoint {
    String attackID;

    ParamNameInsertionPoint(byte[] request, String name, String value, byte type, String attackID) {
        super(request, name, value, type);
        this.attackID = attackID;
    }

    String calculateValue(String unparsed) {
        return Utilities.toCanary(unparsed) + attackID + value;
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        return buildRequest(payload, request);
    }

    public byte[] buildRequest(byte[] payload, byte[] inputRequest) {
        String bulk = Utilities.helpers.bytesToString(payload);
        String[] params = bulk.split("[|]");
        byte[] built = inputRequest;
        for (String name: params) {
            String paramValue;
            if (name.contains("~")) {
                String[] parts = name.split("~", 2);
                name = parts[0];
                paramValue = String.valueOf(Utilities.invert(parts[1]));
            }
            else {
                paramValue = calculateValue(name);
            }

            name = Utilities.encodeParam(name);
            IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(paramValue), type);
            built = Utilities.helpers.updateParameter(built, newParam);
        }
        return built;
    }
}

class RailsInsertionPoint extends ParamNameInsertionPoint {
    String defaultPrefix;

    RailsInsertionPoint(byte[] request, String name, String value, byte type, String attackID) {
        super(request, name, value, type, attackID);
        ArrayList<String> keys = Keysmith.getAllKeys(request, new HashMap<>());
        HashMap<String, Integer> freq = new HashMap<>();
        for (String key: keys) {
            if (key.contains(":")) {
                String object = key.split(":")[0];
                freq.put(object, freq.getOrDefault(object, 0) + 1);
            }
        }

        String maxKey = null;
        int max = 0;
        for (Map.Entry<String, Integer> entry: freq.entrySet()) {
            if (entry.getValue() > max) {
                maxKey = entry.getKey();
                max = entry.getValue();
            }
        }
        defaultPrefix = maxKey;

        if (maxKey != null) {
            Utilities.out("Selected default key: "+maxKey);
        }
        else {
            Utilities.out("No default key available");
        }
    }

    public byte[] buildRequest(byte[] payload) {
        byte[] built = request;
        String bulk = Utilities.helpers.bytesToString(payload);
        String[] params = bulk.split("[|]");
        for(String key: params) {
            if (defaultPrefix != null && !key.contains(":")) {
                key = defaultPrefix + ":" + key;
            }
            built = super.buildRequest(Utilities.helpers.stringToBytes(Keysmith.unparseParam(key)), built);
        }
        return built;
    }

}

class JsonParamNameInsertionPoint extends ParamInsertionPoint {
    byte[] headers;
    byte[] body;
    String baseInput;
    String attackID;
    JsonElement root;

    public JsonParamNameInsertionPoint(byte[] request, String name, String value, byte type, String attackID) {
        super(request, name, value, type); // Utilities.encodeJSON(value)
        int start = Utilities.getBodyStart(request);
        this.attackID = attackID;
        headers = Arrays.copyOfRange(request, 0, start);
        body = Arrays.copyOfRange(request, start, request.length);
        baseInput = Utilities.helpers.bytesToString(body);
        root = new JsonParser().parse(baseInput);
    }

    private Object makeNode(ArrayList<String> keys, int i, Object paramValue) {
        if (i+1 == keys.size()) {
            return paramValue;
        }
        else if (Utilities.parseArrayIndex(keys.get(i+1)) != -1) {
            return new ArrayList(Utilities.parseArrayIndex(keys.get(i+1)));
        }
        else {
            return new HashMap();
        }
    }

    String calculateValue(String unparsed) {
        return Utilities.toCanary(unparsed) + attackID + value;
    }


    @Override
    @SuppressWarnings("unchecked")
    public byte[] buildRequest(byte[] payload) throws RuntimeException {
        String[] params = Utilities.helpers.bytesToString(payload).split("[|]");
        String lastBuild = baseInput;

        try {
            for (String unparsed: params) {

                Object paramValue;
                if (unparsed.contains("~")) {
                    String[] parts = unparsed.split("~", 2);
                    unparsed = parts[0];
                    paramValue = Utilities.invert(parts[1]);
                } else {
                    paramValue = calculateValue(unparsed);
                }

                ArrayList<String> keys = new ArrayList<>(Arrays.asList(unparsed.split(":")));

                boolean isArray = Utilities.parseArrayIndex(keys.get(0)) != -1;
                Object base;
                if (isArray) {
                    try {
                        base = new ObjectMapper().readValue(lastBuild, ArrayList.class);
                    }
                    catch (MismatchedInputException e) {
                        base = new ArrayList();
                    }
                } else {
                    try {
                        base = new ObjectMapper().readValue(lastBuild, HashMap.class);
                    }
                    catch (MismatchedInputException e) {
                        base = new HashMap();
                    }
                }

                Object next = base;
                for (int i = 0; i < keys.size(); i++) {

                    try {
                        String key = keys.get(i);
                        boolean setValue = i + 1 == keys.size();

                        int index = Utilities.parseArrayIndex(key);
                        if (index != -1) {
                            ArrayList injectionPoint = (ArrayList) next;
                            if (injectionPoint.size() < index + 1) {
                                for (int k = injectionPoint.size(); k < index; k++) {
                                    injectionPoint.add(Utilities.generateCanary());
                                }
                                injectionPoint.add(makeNode(keys, i, paramValue));
                            } else if (injectionPoint.get(index) == null || setValue) {
                                injectionPoint.set(index, makeNode(keys, i, paramValue));
                            }
                            next = injectionPoint.get(index);
                        } else {
                            HashMap injectionPoint = (HashMap) next;
                            if (!injectionPoint.containsKey(key) || setValue) {
                                injectionPoint.put(key, makeNode(keys, i, paramValue));
                            }
                            next = injectionPoint.get(key);
                        }
                    } catch(ClassCastException e) {
                        //Utilities.out("Cast error"); // todo figure out a sensible action to stop this form occuring
                    }
                }

                lastBuild = new ObjectMapper().writeValueAsString(base);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(headers);
            outputStream.write(Utilities.helpers.stringToBytes(lastBuild));
            return Utilities.fixContentLength(outputStream.toByteArray());
        } catch (Exception e) {
            Utilities.out("Error with " + String.join(":", params));
            e.printStackTrace(new PrintStream(Utilities.callbacks.getStdout()));
            return buildRequest(Utilities.helpers.stringToBytes("error_" + String.join(":", params).replace(":", "_")));
            // throw new RuntimeException("Request creation unexpectedly failed: "+e.getMessage());
        }
    }
}






