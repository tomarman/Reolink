/**
 * Reolink Webhook Handler (with responding device + state storage)
 *
 * - Parses POSTed JSON (with fallback)
 * - Handles alarm as object or list
 * - Optional token-based auth
 * - Stores state.camera, state.event, state.timeRaw
 * - Invokes a configured device with a chosen action
 *
 * Install/enable OAuth for external access if you plan to receive requests from outside your LAN.
 */
definition(
    name: "Reolink Webhook",
    namespace: "tArman",
    author: "TArman",
    description: "Handles POSTed JSON data at a custom endpoint and triggers a device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    oauth: true
)

preferences {
    section("Title") {
        paragraph "This app handles POST from Reolink devices and optionally triggers a device in response."
    }
    section("Options") {
        input name: "requireAuth", type: "bool", title: "Require access_token / Bearer auth?", defaultValue: true, required: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging (auto off after 30 minutes)", defaultValue: false, required: true
    }
    section("Responding device (optional)") {
        input name: "responseSwitch", type: "capability.actuator", title: "Trigger Indicator", required: false, multiple: false
        input name: "responseButton", type: "capability.pushableButton", title: "Button device (button 1)", required: false, multiple: false
        input name: "responseNotification", type: "capability.notification", title: "Notification-capable device (deviceNotification)", required: false, multiple: false
        input name: "responseAction", type: "enum", title: "Action to perform when webhook received", required: true, options: ["None","Capture Trigger","Button","Push Message"], defaultValue: "None"
    }
}

mappings {
    path("/webhook") {
        action: [
            POST: "postHandler"
        ]
    }
}

def installed() { initialize() }
def updated()   { initialize() }

private void initialize() {
    initializeAppEndpoint()
    if (debugLogging)  runIn(1800, "disableDebug") // auto-disable debug after 30 minutes
    log.info "Initialized Reolink Webhook Handler (requireAuth=${settings.requireAuth}, action=${settings.responseAction})"
	
}

private void disableDebug() {
    app.updateSetting("debugLogging",[value:"false",type:"bool"])
    log.info "Debug logging disabled"
}

def postHandler() {
    if (settings.requireAuth && !isAuthorizedRequest()) {
        log.warn "Unauthorized request"
        render contentType: "application/json", status: 401, data: [status: "unauthorized"]
        return
    }
  
    def body = null
    try {
        body = request?.JSON
        if (!body) {
            def raw = request?.inputStream?.text
            log.warn "Empty JSON body RAW=${raw}"
            if (raw) {
                body = new groovy.json.JsonSlurper().parseText(raw)
            }
        }
    } catch (Exception e) {
        log.error "Failed to parse JSON body: ${e}"
        render contentType: "application/json", status: 400, data: [status: "error", message: "Invalid JSON"]
        return
    }

    if (!body) {
        log.warn "Empty request body"
        render contentType: "application/json", status: 400, data: [status: "error", message: "Empty request body"]
        return
    }

//    if (debugLogging) log.debug "Received POST data: ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(body))}"

    // Support alarm as array or map
    def alarm = body?.alarm
    if (alarm instanceof List) {
        alarm = alarm.size() > 0 ? alarm[0] : null
    }

    if (!alarm) {
        log.warn "No alarm object found in payload"
        render contentType: "application/json", status: 400, data: [status: "error", message: "Missing alarm"]
        return
    }

    // Extract fields with fallbacks
    def camera       = alarm.device ?: alarm.channelName ?: alarm.channel
    def event        = alarm.type ?: alarm.name ?: alarm.message
    def timeRaw      = alarm.alarmTime ?: alarm.time
	def message		 = alarm.message ?: "Missing"
    
    // Try parse date (non-fatal)
    Date eventTime = null
    if (timeRaw) {
        try {
            eventTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", timeRaw.toString())
        } catch (Exception e) {
            if (debugLogging) log.warn "Could not parse alarmTime '${timeRaw}': ${e}"
        }
    }

    // Save into state for later reference
    state.camera = camera
    state.event = event
    state.timeRaw = timeRaw
    state.message = message
    
    // Perform configured response (non-blocking try/catch)
    performConfiguredResponse(camera, event, timeRaw, message)

    log.info "Camera='${camera}', Event='${event}', Time='${timeRaw}', Message='${message}'"

    render contentType: "application/json", data: [status: "received", camera: camera, event: event, time: timeRaw, message: message]
}

/** Perform the user-configured action on the selected device(s). */
private void performConfiguredResponse(def camera, def event, def timeRaw, def text) {
    def action = settings.responseAction ?: "None"
    def message = "Reolink: ${text} from ${camera ?: 'unknown'} - ${event ?: 'unknown'} at ${timeRaw ?: ''}"

    try {
        switch(action) {
            case "Capture Trigger":
                if (responseSwitch) {
                    if (debugLogging) log.debug "Performing addPresence(${event}) on ${responseSwitch}"
                    responseSwitch.addPresence(event)
                } else {
                    if (debugLogging) log.debug " no responseSwitch configured"
                }
                break
           case "Button":
                if (responseButton) {
                    if (debugLogging) log.debug "Performing Button (push) on ${responseButton}"
                    // Some momentary devices expose push(), some may use on() for a pulse - try push first
                    try { responseButton.push(1) } catch(Exception e) { responseButton.on() }
                } else {
                    if (debugLogging) log.debug "Button selected but no responseButton configured"
                }
                break
            case "Push Message":
                if (responseNotification) {
                    if (debugLogging) log.debug "Sending deviceNotification to ${responseNotification}: ${message}"
                    try { responseNotification.deviceNotification(message) } catch(Exception e) {
                        // fallback to generic sendPush if available
                        if (debugLogging) log.warn "deviceNotification failed: ${e}. Trying sendPush..."
                        try { sendPush(message) } catch(Exception ex) { if (debugLogging) log.warn "sendPush also failed: ${ex}" }
                    }
                } else {
                    // no notification device; try hub-level push
                    if (debugLogging) log.debug "No responseNotification configured, attempting hub-level sendPush"
                    try { sendPush(message) } catch(Exception e) { if (debugLogging) log.warn "sendPush failed: ${e}" }
                }
                break
            default:
                if (debugLogging) log.debug "No response action configured (action=${action})"
                break
        }
    } catch (Exception e) {
        log.warn "Error performing configured response: ${e}"
    }
}

/** Simple authorization check:
 *  - Accepts access_token as query param OR Authorization: Bearer <token>
 *  - Compares to state.accessToken created by createAccessToken()
 */
private Boolean isAuthorizedRequest() {
    try {
        def tokenParam = params?.access_token
        def authHeader = request?.headers?.Authorization ?: request?.headers?.authorization
        def bearerToken = authHeader ? authHeader.replaceFirst(/(?i)Bearer\s+/, "") : null
        def incoming = tokenParam ?: bearerToken
        if (!incoming) return false
        if (!state.accessToken) {
            // attempt to create one if missing (best effort)
            try { createAccessToken() } catch(Exception e) { if (debugLogging) log.warn "createAccessToken failed: ${e}" }
        }
        return incoming == state.accessToken
    } catch (Exception e) {
        if (debugLogging) log.warn "isAuthorizedRequest error: ${e}"
        return false
    }
}

private void initializeAppEndpoint(Boolean forceNewAccessToken=false) {
   log.info "initializeAppEndpoint()"
   if (!state.accessToken || forceNewAccessToken) {
      try {
         log.info "Creating access token..."
         createAccessToken()
      }
      catch(Exception ex) {
         log.warn "Failed to generate access token: $ex"
         state.remove("accessToken")
      }
   }
}