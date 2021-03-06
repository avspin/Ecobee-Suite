/**
 *  ecobee Suite Smart Zones
 *
 *  Copyright 2017 Barry A. Burke
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0  
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *	1.0.0  - Final prep for General Release
 *	1.0.1  - Edits to LOG and setup for consistency
 *	1.0.2  - Updated settings and TempDisable handling
 *  1.2.0  - Sync version number with new holdHours/holdAction support
 *	1.2.1  - Protect against LOG type errors
 *	1.3.0  - Major Release: renamed and moved to "sandood" namespace
 *	1.4.0  - Rename parent Ecobee Suite Manager
 *	1.4.01 - Updated description
 *	1.4.02 - Added heat/cool "leeching", improved fan only sharing
 *	1.5.00 - Release number synchronization
 *	1.5.01 - Allow Ecobee Suite Thermostats only
 *	1.5.02 - Converted all math to BigDecimal
 *	1.6.00 - Release number synchronization
 *	1.6.10 - Resync for parent-based reservations
 *	1.6.11 - Removed use of *SetpointDisplay
 *	1.7.00 - Initial Release of Universal Ecobee Suite
 *	1.7.01 - nonCached currentValue() on HE
 */
def getVersionNum() { return "1.7.01" }
private def getVersionLabel() { return "Ecobee Suite Smart Zones Helper,\nversion ${getVersionNum()} on ${getHubPlatform()}" }

definition(
	name: "ecobee Suite Smart Zones",
	namespace: "sandood",
	author: "Barry A. Burke (storageanarchy at gmail dot com)",
	description: "INSTALL USING ECOBEE SUITE MANAGER ONLY!\n\nSynchronizes ecobee recirculation fan between two zones",
	category: "Convenience",
	parent: "sandood:Ecobee Suite Manager",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/ecobee.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/ecobee@2x.png",
	singleInstance: false,
    pausable: true
)

preferences {
	page(name: "mainPage")
}

// Preferences Pages
def mainPage() {
	dynamicPage(name: "mainPage", title: "${getVersionLabel()}", uninstall: true, install: true) {
    	section(title: "") {
        	String defaultLabel = "Smart Zones"
        	label(title: "Name for this ${defaultLabel} Helper", required: true, defaultValue: defaultLabel)
            if (!app.label) {
				app.updateLabel(defaultLabel)
				atomicState.appDisplayName = defaultLabel
			}
			if (isHE) {
				if (app.label.contains('<span ')) {
					if (atomicState?.appDisplayName != null) {
						app.updateLabel(atomicState.appDisplayName)
					} else {
						String myLabel = app.label.substring(0, app.label.indexOf('<span '))
						atomicState.appDisplayName = myLabel
						app.updateLabel(myLabel)
					}
				}
			} else {
            	if (app.label.contains(' (paused)')) {
                	String myLabel = app.label.substring(0, app.label.indexOf(' (paused)'))
                    atomicState.appDisplayName = myLabel
                    app.updateLabel(myLabel)
                } else {
                	atomicState.appDisplayName = app.label
                }
            }
        	if(settings.tempDisable) { 
				paragraph "WARNING: Temporarily Paused - re-enable below."
			} else {
				input ("masterThermostat", "${isST?'device.ecobeeSuiteThermostat':'device.EcobeeSuiteThermostat'}", title: "Master Ecobee Thermostat", required: true, multiple: false, submitOnChange: true)
			}            
		}
        
        if (!settings?.tempDisable && settings?.masterThermostat) {
			section(title: "Select Slave Thermostats") {
				// Settings option for using Mode or Routine
				input(name: "slaveThermostats", title: "Pick Slave Ecobee Thermostat(s)", type: "${isST?'device.ecobeeSuiteThermostat':'device.EcobeeSuiteThermostat'}", required: true, multiple: true, submitOnChange: true)
			}
			if (slaveThermostats) {
				section(title: "Slave Thermostat Actions") {
					input(name: 'shareHeat', title: "Share ${masterThermostat.displayName} heating?", type: "bool", required: true, defaultValue: false, submitOnChange: true)
					input(name: 'shareCool', title: "Share ${masterThermostat.displayName} cooling?", type: "bool", required: true, defaultValue: false, submitOnChange: true)
					if (!settings.shareHeat && !settings.shareCool && !settings.shareFan) {
						input(name: 'shareFan',  title: "Share ${masterThermostat.displayName} fan only?", type: "bool", required: true, defaultValue: true, submitOnChange: true)
					} else {
						input(name: 'shareFan',  title: "Share ${masterThermostat.displayName} fan only?", type: "bool", required: true, /*defaultValue: true,*/ submitOnChange: true)
					}
				}
			}
		}
        
		section(title: "Temporarily Disable?") {
        	input(name: "tempDisable", title: "Pause this Helper?", type: "bool", required: false, description: "", submitOnChange: true)                
        }
        
        section (getVersionLabel()) {}
    }
}

// Main functions
void installed() {
	LOG("installed() entered", 5)
	initialize()  
}

void updated() {
	LOG("updated() entered", 5)
	unsubscribe()
    initialize()
}

def initialize() {
	LOG("${getVersionLabel()}\nInitializing...", 2, "", 'info')
	updateMyLabel()
	
	// Get slaves into a known state
	slaveThermostats.each { stat ->
		String ncTh= isST ? stat.currentValue('thermostatHold') : stat.currentValue('thermostatHold', true) 
    	if (ncTh == 'hold') {
        	if (state."${stat.displayName}-currProg" == null) {
				state."${stat.displayName}-currProg" = isST ? stat.currentValue('currentProgram') : stat.currentValue('currentProgram', true)
            }
            if (state."${stat.displayName}-holdType" == null) {
        		state."${stat.displayName}-holdType" = isST ? stat.currentValue('lastHoldType') : stat.currentValue('lastHoldType', true)
            }
        } else {
      		state."${stat.displayName}-currProg" = null
        	state."${stat.displayName}-holdType" = null
        }
	}
	
	// Now, just exit if we are disabled...
	if(tempDisable == true) {
    	LOG("Temporarily Paused", 2, null, "warn")
    	return true
    }
	
	// check the master, but give it a few seconds first
	runIn (5, "theAdjuster", [overwrite: true])
	
	// and finally, subscribe to thermostatOperatingState changes
	subscribe( masterThermostat, 'thermostatOperatingState', masterFanStateHandler )
    subscribe( slaveThermostats, 'thermostatOperatingState', changeHandler )
    subscribe( slaveThermostats, 'temperature', changeHandler )
    subscribe( slaveThermostats, 'heatingSetpoint', changeHandler )
    subscribe( slaveThermostats, 'coolingSetpoint', changeHandler )
    subscribe( slaveThermostats, 'temperature', changeHandler )
    // subscribe( slaveThermostats, 'currentProgram', changeHandler )
    
    LOG("initialize() complete", 3, null, 'trace')
}

def changeHandler(evt) {
	LOG("${evt.displayName} ${evt.name} ${evt.value}",4,null,'trace')
	runIn(2, 'theAdjuster', [overwrite: true])		// collapse multiple simultaneous events
}
def masterFanStateHandler(evt=null) {
	LOG("${evt.displayName} ${evt.name} ${evt.value}",4,null,'trace')
	runIn(2, 'theAdjuster', [overwrite: true])		// (backwards compatibility
}

def theAdjuster() {
	def masterOpState = isST ? masterThermostat.currentValue('thermostatOperatingState') : masterThermostat.currentValue('thermostatOperatingState', true)
	LOG("theAdjuster() - master thermostatOperatingState = ${masterOpState}", 3, null, 'info')
	
	switch (masterOpState) {
		case 'fan only':
			// master is fan-only, turn on fan for any slaves not already running their fan
            if ((settings.shareFan == null) || settings.shareFan) {
				slaveThermostats.each { stat ->
                	def statOpState = isST ? stat.currentValue('thermostatOperatingState') : stat.currentValue('thermostatOperatingState', true)
					if (statOpState == 'idle') {
						setFanOn(stat) 	// stat.setThermostatFanMode('on', 'nextTransition')
					}
                }
			} else if (settings.shareHeat || settings.shareCool) {
            	slaveThermostats.each { stat ->
            		setFanAuto(stat)
                }
            }
			break;
            
		case 'idle':
        	slaveThermostats.each { stat ->
				ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
            	if (ncCpn == 'Hold: Fan On') {
                    setFanAuto(stat)
                }
            }
            break;
            
		case 'heating':
        	if (settings.shareHeat) {
            	slaveThermostats.each { stat ->
                	def statOpState = isST ? stat.currentValue('thermostatOperatingState') : stat.currentValue('thermostatOperatingState', true)
                	if (statOpState == 'heating') {
                    	setFanAuto(stat) // stat.resumeProgram(true)		// should get us back to desired fan mode
                    } else if (statOpState == 'fanOnly') {
                    	// See if we are holding the fan but don't need the heat any more
						String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
                        if (ncCpn == 'Hold: Fan On') {
                      		def heatTo = isST ? stat.currentValue('heatingSetpoint') : stat.currentValue('heatingSetpoint', true)
                        	if (heatTo != null) {
                        		def temp = isST ? stat.currentValue('temperature') : stat.currentValue('temperature', true)
                            	if (temp != null) {
                            		def heatAt = isST ? stat.currentValue('heatAtSetpoint') : stat.currentValue('heatAtSetpoint', true)
                                	if (heatAt != null) {
                            			if ((temp >= heatTo) || (temp < heatAt)) { 
                                        	// This Zone has reached its target, stop stealing heat
                                			setFanAuto(stat) // stat.setThermostatFanMode('on', 'nextTransition')		// turn the fan on to leech some heat
                                        }
                                    }
                                }
                            }
                        }
                    } else if (statOpState == 'idle') {
                    	def heatTo = isST ? stat.currentValue('heatingSetpoint') : stat.currentValue('heatingSetpoint', true)
                        if (heatTo != null) {
                        	def temp = isST ? stat.currentValue('temperature') : stat.currentValue('temperature', true)
                            if (temp != null) {
                            	def heatAt = isST ? stat.currentValue('heatAtSetpoint') : stat.currentValue('heatAtSetpoint', true)
                                if (heatAt != null) {
                            		if ((temp < heatTo) && (temp > heatAt)) { 
                                		setFanOn(stat) // stat.setThermostatFanMode('on', 'nextTransition')		// turn the fan on to leech some heat		
                                    }
                                }
                            }
                        }
                    } else { // Must be 'cooling'
						String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
                        if (ncCpn == 'Hold: Fan On') setFanAuto(stat)	// Just make sure fan is in Auto mode
                    }
                }
            } else {
            	// not sharing (leeching) heat
                if ((settings.shareFan == null) || settings.shareFan) {
                	// but we are sharing fan - turn off fan to avoid overheating this zone
                	slaveThermostats.each { stat ->
						String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
                		if (ncCpn == 'Hold: Fan On') {
                        	setFanAuto(stat)
                        }
                    }
                }
            }
            break;
            
		case 'cooling':
			if (settings.shareCool) {
				slaveThermostats.each { stat->
                    def statOpState = isST ? stat.currentValue('thermostatOperatingState') : stat.currentValue('thermostatOperatingState', true)
					if (statOpState == 'cooling') {
                    	// We are cooling too - double-check that fan is in Auto mode
                        setFanAuto(stat)				
                    } else if (statOpState == 'fanOnly') {
                    	// Check if we are holding the fan but don't need the cool any more
						String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
                        if (ncCpn == 'Hold: Fan On') {
                      		def coolTo = isST ? stat.currentValue('coolingSetpoint') : stat.currentValue('coolingSetpoint', true)
                        	if (coolTo != null) {
                        		def temp = isST ? stat.currentValue('temperature') : stat.currentValue('temperature', true)
                            	if (temp != null) {
                            		def coolAt = isST ? stat.currentValue('coolAtSetpoint') : stat.currentValue('coolAtSetpoint', true)
                                	if (coolAt != null) {
                            			if ((temp <= coolTo) || (temp > coolAt)) { 
                                        	// This Zone has reached its target, stop stealing cool
                                			setFanAuto(stat) // stat.setThermostatFanMode('on', 'nextTransition')		// turn the fan on to leech some heat
                                        }
                                    }
                                }
                            }
                        }
                    } else if (statOpState == 'idle') {
                    	// Check if we need the cool
                    	def coolTo = isST ? stat.currentValue('coolingSetpoint') : stat.currentValue('coolingSetpoint', true)
                        if (coolTo != null) {
                        	def temp = isST ? stat.currentValue('temperature') : stat.currentValue('temperature', true)
                            if (temp != null) {
                            	def coolAt = isST ? stat.currentValue('coolAtSetpoint') : stat.currentValue('coolAtSetpoint', true)
                                if (coolAt != null) {
                            		if ((temp > coolSp) && (temp < coolAt)) {
                                	   	setFanOn(stat)
                                    }
                                }
                    		}
                        }
                    } else { // Must be 'heating'
						String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
                        if (ncCpn == 'Hold: Fan On') setFanAuto(stat)	// Just make sure fan is in Auto mode
                    }
                }
            } else {
            	// not sharing (leeching) cool
                if ((settings.shareFan == null) || settings.shareFan) {
                	// but we are sharing fan - turn off fan to avoid overcooling this zone
                	slaveThermostats.each { stat ->
						String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
                		if (ncCpn == 'Hold: Fan On') {	// set auto for now, so we don't break fanMinOnTime/Circulation
                        	setFanAuto(stat)
                        }
                    }
                }
            }
            break;

		default:
        	// we ignore the other possible OperatingStates (e.g., 'pendhing heat', etc.)
            slaveThermostats.each { stat ->
				String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
            	if (ncCpn == 'Hold: Fan On') setFanAuto(stat)
            }
			break;
	}
}

def setFanAuto(stat) {
	def oldProg = state."${stat.displayName}-currProg"
    if (oldProg) {
		String ncCp = isST ? stat.currentValue('currentProgram') : stat.currentValue('currentProgram', true)
    	if (ncCp != oldProg) stat.setThermostatProgram(oldProg, state."${stat.displayName}-holdType")
        state."${stat.displayName}-currProg" = null
        state."${stat.displayName}-holdType" = null
    }
	def fanMOT = isST ? stat.currentValue('fanMinOnTime') : stat.currentValue('fanMinOnTime', true)
    if ((fanMOT != null) && (fanMOT != 0)) {
		String ncTfmd = isST ? stat.currentValue('thermostatFanModeDisplay') : stat.currentValue('thermostatFanModeDisplay', true)
    	if (ncTfmd != 'circulate') {
        	stat.setThermostatFanMode('circulate')
            LOG("${stat.displayName} fanMode = circulate", 3)
        }
    } else {
		String ncTfm = isST ? stat.currentValue('thermostatFanMode') : stat.currentValue('thermostatFanMode', true)
    	if (ncTfm != 'auto') {
        	stat.setThermostatFanMode('auto')
            LOG("${stat.displayName} fanMode = auto", 3)
        }
    }
}

def setFanOn(stat) {
	String ncCpn = isST ? stat.currentValue('currentProgramName') : stat.currentValue('currentProgramName', true)
	String ncTfm = isST ? stat.currentValue('thermostatFanMode') : stat.currentValue('thermostatFanMode', true)
	if ((ncCpn != 'Hold: Fan On') || (ncTfm != 'on')) {
		String ncTh = isST ? stat.currentValue('thermostatHold') : stat.currentValue('thermostatHold', true)
    	if (ncTh == 'hold') {
        	state."${stat.displayName}-holdType" = isST ? stat.currentValue('lastHoldType') : stat.currentValue('lastHoldType', true)
        	state."${stat.displayName}-currProg" = isST ? stat.currentValue('currentProgram') : stat.currentValue('currentProgram', true)
        }
    	stat.setThermostatFanMode('on', 'nextTransition')
        LOG("${stat.displayName} fanMode = on", 3)
    }
}

// Helper Functions

private def updateMyLabel() {
	String flag = isST ? ' (paused)' : '<span '
	
	// Display Ecobee connection status as part of the label...
	String myLabel = atomicState.appDisplayName
	if ((myLabel == null) || !app.label.startsWith(myLabel)) {
		myLabel = app.label
		if (!myLabel.contains(flag)) atomicState.appDisplayName = myLabel
	} 
	if (myLabel.contains(flag)) {
		// strip off any connection status tag
		myLabel = myLabel.substring(0, myLabel.indexOf(flag))
		atomicState.appDisplayName = myLabel
	}
	if (settings.tempDisable) {
		def newLabel = myLabel + (isHE ? '<span style="color:orange"> Paused</span>' : ' (paused)')
		if (app.label != newLabel) app.updateLabel(newLabel)
	} else {
		if (app.label != myLabel) app.updateLabel(myLabel)
	}
}

private def LOG(message, level=3, child=null, logType="debug", event=true, displayEvent=true) {
	message = "${app.label} ${message}"
	if (logType == null) logType = 'debug'
	parent.LOG(message, level, null, logType, event, displayEvent)
    log."${logType}" message
}

// **************************************************************************************************************************
// SmartThings/Hubitat Portability Library (SHPL)
// Copyright (c) 2019, Barry A. Burke (storageanarchy@gmail.com)
//
// The following 3 calls are safe to use anywhere within a Device Handler or Application
//  - these can be called (e.g., if (getPlatform() == 'SmartThings'), or referenced (i.e., if (platform == 'Hubitat') )
//  - performance of the non-native platform is horrendous, so it is best to use these only in the metadata{} section of a
//    Device Handler or Application
//
//	1.0.0	Initial Release
//	1.0.1	Use atomicState so that it is universal
//
private String  getPlatform() { return (physicalgraph?.device?.HubAction ? 'SmartThings' : 'Hubitat') }	// if (platform == 'SmartThings') ...
private Boolean getIsST()     { return (atomicState?.isST != null) ? atomicState.isST : (physicalgraph?.device?.HubAction ? true : false) }					// if (isST) ...
private Boolean getIsHE()     { return (atomicState?.isHE != null) ? atomicState.isHE : (hubitat?.device?.HubAction ? true : false) }						// if (isHE) ...
//
// The following 3 calls are ONLY for use within the Device Handler or Application runtime
//  - they will throw an error at compile time if used within metadata, usually complaining that "state" is not defined
//  - getHubPlatform() ***MUST*** be called from the installed() method, then use "state.hubPlatform" elsewhere
//  - "if (state.isST)" is more efficient than "if (isSTHub)"
//
private String getHubPlatform() {
	def pf = getPlatform()
    atomicState?.hubPlatform = pf			// if (atomicState.hubPlatform == 'Hubitat') ... 
											// or if (state.hubPlatform == 'SmartThings')...
    atomicState?.isST = pf.startsWith('S')	// if (atomicState.isST) ...
    atomicState?.isHE = pf.startsWith('H')	// if (atomicState.isHE) ...
    return pf
}
private Boolean getIsSTHub() { return atomicState.isST }					// if (isSTHub) ...
private Boolean getIsHEHub() { return atomicState.isHE }					// if (isHEHub) ...

private def getParentSetting(String settingName) {
	// def ST = (atomicState?.isST != null) ? atomicState?.isST : isST
	//log.debug "isST: ${isST}, isHE: ${isHE}"
	return isST ? parent?.settings?."${settingName}" : parent?."${settingName}"	
}
//
// **************************************************************************************************************************
