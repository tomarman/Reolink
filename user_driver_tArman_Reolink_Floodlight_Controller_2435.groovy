/**
 * Reolink Elite Wifi Floodlight Camera Controller for Hubitat
 *
 * Controls the floodlight via Reolink's HTTP API (Login, then SetWhiteLed).
 * Auth token is obtained on demand and cached in state["reolinkToken"].
 *
 * Token retry behavior:
 *   On a 401 response from SetWhiteLed, the cached token is cleared, a new
 *   token is obtained via Login, and the original command (same desiredState
 *   and desiredMode) is retried exactly once.
 *
 * refresh() behavior:
 *   refresh() is intentionally a RESET-TO-AUTO operation. It turns the
 *   floodlight off and then sets mode to "auto" (motion-triggered).
 *   This is by design — it is not a read-only status query.
 *
 * Author: TArman (2024)
 */

metadata {
    definition(name: "Reolink Floodlight Controller", namespace: "tArman", author: "TArman") {
        capability "Switch"
        attribute "mode", "string"
        command "refresh"
        command "turnOnFloodlight"
        command "turnOffFloodlight"
        command "setFloodlightMode", [[name: "mode", type: "ENUM", constraints: ["off","auto","on","timer"], description: "Floodlight mode: off, auto=Motion, on at night, timer"]]
    }
    preferences {
        input("ip", "string", title: "Camera IP Address", required: true)
        input("username", "string", title: "Camera Username", required: true)
        input("password", "password", title: "Camera Password", required: true)
    }
}

def stateTokenKey = "reolinkToken"

// On install/updated
def installed() { initialize() }
def updated() { initialize() }
def initialize() {
    state.remove(stateTokenKey)
    refresh()
}

def on() {
    turnOnFloodlight()
}

def off() {
    turnOffFloodlight()
}

// refresh() intentionally resets the floodlight to auto/motion mode.
// Turns the light off first, then switches mode to "auto" (motion-triggered).
def refresh() {
    ensureToken { token ->
        // Step 1: turn light off
        sendFloodlightCommand(0, token, "off")
        // Step 2: restore auto/motion mode
        setFloodlightMode("auto")
    }
}

def turnOnFloodlight() {
    ensureToken { token ->
        sendFloodlightCommand(1, token, "on")
    }
}

def turnOffFloodlight() {
    ensureToken { token ->
        sendFloodlightCommand(0, token, "off")
    }
}

// Set floodlight mode using an enum/selector
def setFloodlightMode(mode) {
    def modeMap = [off:0, auto:1, on:2, timer:3]
    def modeVal = modeMap[mode]
    if(modeVal==null) {
        log.warn "Invalid mode '${mode}' (valid: off, auto, on, timer)"
        return
    }
    ensureToken { token ->
        sendFloodlightModeCommand(modeVal, token)
    }
}

// Obtains and stores a fresh token if needed, then runs closure
private ensureToken(closure) {
    if (state[stateTokenKey]) {
        closure(state[stateTokenKey])
    } else {
        getToken { token ->
            if (token) {
                state[stateTokenKey] = token
                closure(token)
            } else {
                log.error "Failed to obtain token!"
            }
        }
    }
}

// Logs in and obtains a token, calls closure(token)
private getToken(closure) {
//        log.debug "Token obtaining"
    def loginBody = [
        [
            "cmd": "Login",
            "action": 0,
            "param": [
                "User": [
                    "userName": settings.username,
                    "password": settings.password
                ]
            ]
        ]
    ]
    def params = [
        uri: "http://${settings.ip}/cgi-bin/api.cgi?cmd=Login",
        contentType: "application/json",
        body: groovy.json.JsonOutput.toJson(loginBody)
    ]
//    log.debug "Token http call: ${params}"
    asynchttpPost("processLoginResponse", params, [closure: closure])
}

def processLoginResponse(resp, data) {
    def closure = data.closure
    if (resp.getStatus() == 200 && resp.getJson()) {
        def token = resp.getJson()[0]?.value?.Token?.name
 //       log.debug "Token obtained: $token"
        closure(token)
    } else {
        log.error "Login failed, resp: ${resp.getData() ?: resp.getErrorMessage()}"
        closure(null)
    }
}

// Sends HTTP command to control floodlight (desiredState: 1=on, 0=off, desiredMode: off/auto/on/timer)
private sendFloodlightCommand(desiredState, token, desiredMode, retryOnAuthFail = true) {
    def modeMap = [off:0, auto:1, on:2, timer:3]
    def modeVal = modeMap[desiredMode]
    if (modeVal == null) {
        log.warn "Invalid mode '${desiredMode}' for sendFloodlightCommand (valid: off, auto, on, timer). Command not sent."
        return
    }
    def cmdBody = [
        [
            cmd: "SetWhiteLed",
            action: 0,
            param: [
                WhiteLed: [
                    state: desiredState,
                    mode: modeVal,
                    channel: 0
                ]
            ]
        ]
    ]
    def params = [
        uri : "http://${settings.ip}/cgi-bin/api.cgi?cmd=SetWhiteLed&token=${token}",
        contentType: "application/json",
        body: groovy.json.JsonOutput.toJson(cmdBody)
    ]
    // hasRetried=true means we already retried once; do not retry again
    asynchttpPost("processFloodlightResponse", params, [desiredState: desiredState, desiredMode: desiredMode, hasRetried: !retryOnAuthFail])
}

// Sends HTTP command to set floodlight mode only
private sendFloodlightModeCommand(modeVal, token, retryOnAuthFail = true) {
    def cmdBody = [
        [
            cmd: "SetWhiteLed",
            action: 0,
            param: [
                WhiteLed: [
                    mode: modeVal,
                    channel: 0
                ]
            ]
        ]
    ]
    def params = [
        uri : "http://${settings.ip}/cgi-bin/api.cgi?cmd=SetWhiteLed&token=${token}",
        contentType: "application/json",
        body: groovy.json.JsonOutput.toJson(cmdBody)
    ]
    // hasRetried=true means we already retried once; do not retry again
    asynchttpPost("processFloodlightModeResponse", params, [desiredMode: modeVal, hasRetried: !retryOnAuthFail])
}

def processFloodlightResponse(resp, data) {
    def desiredState = data.desiredState
    def desiredMode  = data.desiredMode
    if (resp.getStatus() == 200) {
        sendEvent(name: "switch", value: desiredState ? "on" : "off")
        log.info "Floodlight ${desiredState ? 'on' : 'off'} successfully..$data"
    } else if (resp.getStatus() == 401 && !data.hasRetried) {
        // Token expired: clear cached token, obtain a fresh one, and retry with same state and mode
        log.warn "Token expired, refreshing token and retrying..."
        state.remove(stateTokenKey)
        ensureToken { token ->
            sendFloodlightCommand(desiredState, token, desiredMode, false)
        }
    } else {
        log.error "Error setting floodlight: status=${resp.getStatus()}, data=${resp.getData()}"
    }
}

// Handle response from setFloodlightMode
def processFloodlightModeResponse(resp, data) {
    def desiredMode = data.desiredMode
    def modeNames = [0:"off", 1:"auto", 2:"on", 3:"timer"]
    def modeName = modeNames[desiredMode]
    if (resp.getStatus() == 200) {
        log.info "Floodlight mode set to $modeName successfully...$desiredMode"
        state.floodlightMode = desiredMode
        sendEvent(name: "mode", value: modeName)
    } else if (resp.getStatus() == 401 && !data.hasRetried) {
        log.warn "Token expired, refreshing token and retrying (mode)..."
        state.remove(stateTokenKey)
        ensureToken { token ->
            sendFloodlightModeCommand(desiredMode, token, false)
        }
    } else {
        log.error "Error setting floodlight mode: status=${resp.getStatus()}, data=${resp.getData()}"
    }
}
