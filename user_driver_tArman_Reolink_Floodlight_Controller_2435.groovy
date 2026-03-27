/**
 * Reolink Elite Wifi Floodlight Camera Controller for Hubitat
 * Controls the floodlight via Reolink's HTTP API (login, retain/use token, floodlight on/off)
 * Uses SetWhiteLed API command for floodlight
 * Adds setFloodlightMode(mode) command using an enum/selector for mode
 * Author: Copilot GitHub (2024)
 */

metadata {
    definition(name: "Reolink Floodlight Controller", namespace: "tArman", author: "TArman") {
        capability "Switch"
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
def mode = 1

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

def refresh() {
    ensureToken { token ->
        sendFloodlightCommand(0, token, "off")
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

// Sends HTTP command to control floodlight (state: 1=on, 0=off), mode=1 by default
private sendFloodlightCommand(state, token, mode, retryOnAuthFail = true) {
    def modeMap = [off:0, auto:1, on:2, timer:3]
    def modeVal = modeMap[mode]
    def cmdBody = [
        [
            cmd: "SetWhiteLed",
            action: 0,
            param: [
                WhiteLed: [
                    state: state,
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
    asynchttpPost("processFloodlightResponse", params, [desiredState: state, retried: !retryOnAuthFail])
}

// Sends HTTP command to set floodlight mode only
private sendFloodlightModeCommand(mode, token, retryOnAuthFail = true) {
    def cmdBody = [
        [
            cmd: "SetWhiteLed",
            action: 0,
            param: [
                WhiteLed: [
                    mode: mode,
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
    asynchttpPost("processFloodlightModeResponse", params, [desiredMode: mode, retried: !retryOnAuthFail])
}

def processFloodlightResponse(resp, data) {
    def desiredState = data.desiredState
    if (resp.getStatus() == 200) {
        sendEvent(name: "switch", value: desiredState ? "on" : "off")
        log.info "Floodlight ${desiredState ? 'on' : 'off'} successfully..$data"
    } else if (resp.getStatus() == 401 && !data.retried) {
        // Token expired: clear token and retry
        log.warn "Token expired, refreshing token and retrying..."
        state.remove(stateTokenKey)
        ensureToken { token ->
            sendFloodlightCommand(desiredState, token, false)
        }
    } else {
        log.error "Error setting floodlight: status=${resp.getStatus()}, data=${resp.getData()}"
    }
}

// Handle response from setFloodlightMode
def processFloodlightModeResponse(resp, data) {
    def desiredMode = data.desiredMode
    def modeMap = [0:"off", 1:"auto", 2:"on", 3:"timer"]
    def modeVal = modeMap[desiredMode]
    if (resp.getStatus() == 200) {
        log.info "Floodlight mode set to $modeVal successfully...$desiredMode"
        mode = desiredMode
        sendEvent(name: "mode", value: modeVal)
    } else if (resp.getStatus() == 401 && !data.retried) {
        log.warn "Token expired, refreshing token and retrying (mode)..."
        state.remove(stateTokenKey)
        ensureToken { token ->
            sendFloodlightModeCommand(desiredMode, token, false)
        }
    } else {
        log.error "Error setting floodlight mode: status=${resp.getStatus()}, data=${resp.getData()}"
    }
}
