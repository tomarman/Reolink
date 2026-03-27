/**
 *  Reolink-State Driver for Hubitat
 *  -------------------------------------------------------------
 *  Driver that behaves like a virtual switch but supports a multi-state
 *  presence model (people, pets, packages, other). Multiple states may
 *  be active at once.
 *
 *  Capabilities:
 *    - Actuator, Sensor
 *
 *  Attributes:
 *    - activeStates   (string)  : comma-separated list of active states (lowercase)
 *    - peoplePresent  (string)  : "true" / "false"
 *    - petsPresent    (string)  : "true" / "false"
 *    - packagesPresent(string)  : "true" / "false"
 *    - visitorDoorbell(string)  : "true" / "false"
 *    - otherPresent   (string)  : "true" / "false"
 *
 *  Commands:
 *    - setPresence(String presence)       : set a single state (replaces all if presence == "only:..."; see docs)
 *    - addPresence(String presence)       : add one state to the active set
 *    - removePresence(String presence)    : remove one state from the active set
 *    - togglePresence(String presence)    : toggle a single state
 *    - clearPresence()                    : clear all states
 *    - setPresences(String presences)     : set multiple states (comma-separated or JSON array string)
 *
 *  Author: Copied/Adapted by @copilot for the user (tomarman)
 *  Date: 2025-11-29 (fixed parameter shadowing bug)
 */
metadata {
    definition (name: "Reolink-State Driver for Hubitat", namespace: "tArman", author: "TArman") {
 //       capability "Switch"
        capability "Actuator"
        capability "Sensor"

        attribute "activeStates", "string"
        attribute "peoplePresent", "string"
        attribute "petsPresent", "string"
        attribute "packagesPresent", "string"
        attribute "visitorDoorbell", "string"
        attribute "otherPresent", "string"

 //       command "setPresence", ["string"]
        command "addPresence", ["string"]
 //       command "removePresence", ["string"]
 //       command "togglePresence", ["string"]
        command "clearPresence"
 //       command "setPresences", ["string"]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "infoEnable", type: "bool", title: "Enable info logging", defaultValue: true
    }
}

def installed() {
    if (infoEnable) log.info "${device.displayName} installed"
    initialize()
}

def updated() {
    if (infoEnable) log.info "${device.displayName} updated"
    if (logEnable) runIn(1800, disableDebug) // turn off debug after 30m if left on
    initialize()
}

def initialize() {
    // initialize state map if not present
    if (state.activeStates == null) state.activeStates = [] as Set
    // ensure attributes reflect internal state
    syncAttributes()
}

/* ======== PUBLIC COMMANDS ======== */

/**
 * addPresence(presence)
 * Accepts a single state name (people, pets, packages, other). Case-insensitive.
 * If invalid, logs a warning and does nothing.
 */
def addPresence(String presence) {
    if (!presence) return
    def s = normalizeState(presence)
    def set = (stateSet())
    if (!set.contains(s)) {
        set << s
        state.activeStates = set
        logDebug("Added presence: ${s}. Active states now: ${set}")
        syncAttributes()
        sendMyEvent(s)
    } else {
        logDebug("addPresence: '${s}' already active")
    }
}

/**
 * clearPresence()
 */
def clearPresence() {
    state.activeStates = [] as Set
    logDebug("Cleared all presences")
    syncAttributes()
//    sendSwitchEvent()
}

/* ======== INTERNAL HELPERS ======== */

private Set stateSet() {
    if (state.activeStates == null) state.activeStates = [] as Set
    // ensure it's a Set object
    if (state.activeStates instanceof Set) return state.activeStates
    // if persisted as a List or String, normalize to Set
    if (state.activeStates instanceof Collection) {
        state.activeStates = (state.activeStates as List).collect{ it.toString().toLowerCase() } as Set
        return state.activeStates
    }
    if (state.activeStates instanceof String) {
        def arr = state.activeStates.split(",").collect{ it.trim() }.findAll{ it }
        state.activeStates = arr.collect{ it.toLowerCase() } as Set
    } else {
        state.activeStates = [] as Set
    }
    return state.activeStates
}

private sendMyEvent(qqq) {
//    def on = stateSet().size() > 0 ? "on" : "off"
    // Only send event if changed
//    if (device.currentValue("detection") != on) {
        sendEvent(name: "detection", value: qqq)
        logInfo("detection event -> ${qqq}")
//    } else {
//        logDebug("Switch already ${on}")
//    }
}

private syncAttributes() {
    def set = stateSet()

    // activeStates as comma-separated
    def activeStr = set.size() ? set.sort().join(",") : ""
    if (device.currentValue("activeStates") != activeStr) {
        sendEvent(name: "activeStates", value: activeStr)
    }

    // individual boolean attributes as strings "true"/"false" for compatibility
    sendBooleanAttribute("peoplePresent", set.contains("people"))
    sendBooleanAttribute("petsPresent", set.contains("pet"))
    sendBooleanAttribute("packagesPresent", set.contains("packages"))
    sendBooleanAttribute("visitorDoorbell", set.contains("visitor"))
    sendBooleanAttribute("otherPresent", set.contains("other"))
}

private sendBooleanAttribute(attrName, booleanValue) {
    def valStr = booleanValue ? "true" : "false"
    if (device.currentValue(attrName) != valStr) {
        sendEvent(name: attrName, value: valStr)
    }
}

private parseStatesString(String raw) {
    // Accept JSON array string or comma-separated values
    def result = []
    log.debug("json: ${raw}")

    try {
        if (raw.startsWith("[") && raw.endsWith("]")) {
            // attempt JSON parse
            def slurper = new groovy.json.JsonSlurper()
            def parsed = slurper.parseText(raw)
            if (parsed instanceof Collection) {
                parsed.each { result << it.toString() }
                return result
            }
        }
    } catch (Exception e) {
        logWarn("parseStatesString: JSON parse failed, falling back to CSV. Error: ${e}")
    }

    // CSV fallback
    raw.split(",").each { token ->
        def t = token?.trim()
        if (t) result << t
    }
    // Also allow space-separated when no comma present
    if (!result && raw.contains(" ") && !raw.contains(",")) {
        raw.split(/\s+/).each { token -> if (token) result << token }
    }

    // single token
    if (!result) result << raw

    return result
}

private String normalizeState(String s) {
    return s?.toString()?.trim()?.toLowerCase()
}

private boolean isValidState(String s) {
    def valid = ["people","pet","packages","visitor","other"]
    return (s in valid)
}

/* ======== Logging helpers ======== */
private logDebug(msg) {
    if (logEnable) log.debug("${device.displayName}: ${msg}")
}

private logInfo(msg) {
    if (infoEnable) log.info("${device.displayName}: ${msg}")
}

private logWarn(msg) {
    log.warn("${device.displayName}: ${msg}")
}

def disableDebug() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info("${device.displayName}: debug logging disabled")
}

/* ======== Capability Switch commands: keep compatibility ======== *
def on() {
    // Turning the switch "on" without a specified state will set a default: "people"
    // This behavior matches many virtual switches that consider "on" == occupied.
    logDebug("on() called -> setting default presence 'people'")
    addPresence("people")
}

def off() {
    // "off" clears all presence states
    logDebug("off() called -> clearing presences")
    clearPresence()
}
*/