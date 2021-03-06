/**
 *  Ecobee Suite Smart Mode
 *
 *  Copyright 2018 Justin Leonard, Barry A. Burke
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
 *	1.4.0  - Initial release
 *	1.4.01 - Added unschedule() to updated()
 *	1.4.02 - Shortened LOG and NOTIFY strings when reporting on multiple thermostats
 *	1.4.03 - Fix frequency enum translations
 *	1.4.04 - Fixed notifications
 *	1.4.05 - Change the mode only ONCE when crossing a configured threshold (for coexistence with other Helpers/Instances)
 *	1.4.06 - Added more data validation around outside temp sources
 *	1.4.07 - Added inside temperature Mode change options
 *	1.4.08 - Tweaked inside temp change - don't switch to auto if mode is already the correct one (cool/heat)
 *	1.5.00 - Release number synchronization
 *	1.5.01 - Allow Ecobee Suite Thermostats only
 *	1.5.02 - Converted all math to BigDecimal
 *	1.5.03 - Added (optional) dewpoint override for belowTemp Off Mode
 *	1.5.04 - Added modeOff reservation support to avoid conflicts with other Helper Apps
 *	1.5.05 - Added multiple SMS support (Contacts being deprecated by ST)
 *	1.6.00 - Release number synchronization
 *	1.6.01 - Fixed sendMessage()
 *	1.6.02 - Fix reservation initialization error
 *	1.6.03 - REALLY fix reservation initialization error
 *	1.6.04 - Really, REALLY fix reservation initialization error
 *	1.6.10 - Converted to parent-based reservations
 *	1.6.11 - Clear reservations when disabled
 *	1.6.12 - Logic tuning, clear reservations when externally overridden
 *	1.6.13 - Removed location.contactBook - unexpectedly deprecated by SmartThings
 *	1.6.14 - Updated to remove use of *SetpointDisplay
 *  1.6.15 - Fixed external temp range limiter; should now work with either F/C temperature scales
 *	1.6.16 - Fixed initialization error when using SmartThings Sensors
 *	1.6.17 - Added more logging for PWS, calculate dewpoint if not provided by WU
 *	1.6.18 - Switched Zip/GPS external temp source to new getTwcConditions
 *	1.6.19 - Changes Reverted
 *	1.6.20 - Handle null list of Climates
 *	1.6.21 - Added option to change heat/cool setpoints instead of/in addition to changing the mode
 *	1.7.00 - Initial Release of Universal Ecobee Suite
 */
def getVersionNum() { return "1.7.00" }
private def getVersionLabel() { return "Ecobee Suite Smart Mode & Setpoints Helper,\nversion ${getVersionNum()} on ${getHubPlatform()}" }
import groovy.json.*

definition(
	name: "ecobee Suite Smart Mode",
	namespace: "sandood",
	author: "Justin J. Leonard & Barry A. Burke",
	description: "INSTALL USING ECOBEE SUITE MANAGER ONLY!\n\nSets Ecobee Heat/Cool/Auto mode and/or Program Setpoints based on (outside) temperature & dewpoint.",
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

def mainPage() {
	dynamicPage(name: "mainPage", title: "${getVersionLabel()}", uninstall: true, install: true) {
    	section(title: "") {
			String defaultLabel = "Smart Mode & Setpoints"
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
				input ("thermostats", "${isST?'device.ecobeeSuiteThermostat':'device.EcobeeSuiteThermostat'}", title: "Ecobee Thermostat(s)", required: true, 
					   multiple: true, submitOnChange: true)
			}
		}
        if (!settings?.tempDisable && (settings?.thermostats?.size()>0)) {
			section(title: "Outdoor Weather Source") {
				input(name: 'tempSource', title: 'Monitor this weather source', type: 'enum', required: true, multiple: false,  
					  options: (isST?[
						  'ecobee':"Ecobee Thermostat's Weather", 
						  'location':"SmartThings/TWC Weather for ${location.name}", 
						  'sensors':'SmartThings Sensors',
						  'station':'SmartThings-based Weather Station DTH',
						  'wunder':'Weather Underground Station (obsolete)'
                    	]:[
						  'ecobee':"Ecobee Thermostat's Weather",  
						  'sensors':'Hubitat Sensors',
						  'station':'Hubitat-based Weather Station Device',
						  ]), submitOnChange: true
                )
				if (settings.tempSource) {
					if (settings.tempSource == 'location') {
                    	paragraph "Using The Weather Company weather for the current location (${location.name})."
                        if (!settings.latLon) input(name: "zipCode", type: 'text', title: 'Zipcode (Default is location Zip code)', defaultValue: getZIPcode(), required: true, submitOnChange: true )
                        if (location.latitude && location.longitude) input(name: "latLon", type: 'bool', title: "Use ${isST?'SmartThings':'Hubitat'} hub's GPS coordinates instead (better precision)?", submitOnChange: true)
						input(name: 'locFreq', type: 'enum', title: 'Temperature check frequency (minutes)', required: true, multiple: false, 
                        	options:['1','5','10','15','30','60','180'])
					} else if (settings.tempSource == 'sensors') {
                    	paragraph "Using ${isST?'SmartThings':'Hubitat'} sensors. Note: Both Temperature & Humidity sensors are required for dew point-based actions."
						input(name: 'thermometer', type: 'capability.temperatureMeasurement', title: "Which Temperature Sensor?", required: true, multiple: false)
                        input(name: 'humidistat', type: 'capability.relativeHumidityMeasurement', title: "Which Relative Humidity Sensor?",  
                        	required: (settings.dewBelowOverride), multiple: false) 
					} else if (settings.tempSource == "ecobee") {
                    	paragraph "Using weather data from the (notoriously inaccurate) Ecobee thermostat${(settings.thermostats.size()==1)?' '+settings.thermostats.displayName:':'}"
						if (settings.thermostats.size() > 1) {
							input(name: 'tstatTemp', type: 'enum', title: "Which Ecobee Thermostat?", required: true, multiple: false, submitOnChange: true,
                            		options:thermostats.displayName)
						}
					} else if (settings.tempSource == 'station') {
						paragraph "Using a ${isST?'SmartThings':'Hubitat'}-based Weather Station - please select ${isST?'ONE ':''}from the list of the supported Weather Station devices below..."
						if (isST) {
							input(name: "smartWeather", type: "device.smartWeatherStationTile", title: 'Which SmartWeather Station Tile?', required: false, 
									multiple: false, hideWhenEmpty: true)
							input(name: "smartWeather2", type: "device.smartWeatherStationTile2.0", title: 'Which SmartWeather Station Tile 2.0?', required: false, 
									multiple: false, hideWhenEmpty: true)
						}
						input(name: "meteoWeather", type: "${isST?'device.meteobridgeWeatherStation':'device.MeteobridgeWeatherStation'}", title: 'Which Meteobridge Weather Station?', required: false, 
                        		multiple: false, hideWhenEmpty: true)      
                    } else if (settings.tempSource == "wunder") {
                    	paragraph "Using a specific Weather Underground Weather Station"
						input(name: 'stationID', type: 'string', title: 'Enter WeatherUnderground Station identifier', defaultValue: "${settings.nearestPWS?getPWSID():''}", required: true)
                        input(name: 'nearestPWS', type: 'bool', title: 'Use nearest available station', options: ['true', 'false'], defaultValue: true, submitOnChange: true)
        				href(title: "Or, Search WeatherUnderground.com for your desired PWS",
        					description: 'After page loads, select "Change Station" for a list of weather stations.  ' +
        					'You will need to copy the station code into the PWS field above, in the form of "pws:STATIONID"',
             				required: false, style:'embedded',             
             				url: (location.latitude && location.longitude)? "http://www.wunderground.com/cgi-bin/findweather/hdfForecast?query=${location.latitude},${location.longitude}" :
             		 		"http://www.wunderground.com/q/${location.zipCode}")
                        input(name: 'pwsFreq', type: 'enum', title: 'Temperature check frequency (minutes)', required: true, multiple: false, options:['1','5','10','15','30','60','180'])
					}
				}
			}
			section(title: "Outdoor Temperature 'Above' Settings") {
				// need to set min & max - get from thermostat range
       			input(name: "aboveTemp", title: "When the outdoor temperature is at or above...", type: 'decimal', description: "Enter decimal temperature (${settings.belowTemp?'optional':'required'})", 
                		range: getThermostatRange(), required: !settings.belowTemp, submitOnChange: true)
                input(name: "dewAboveTemp", title: "Or, (optionally) when the outdoor dewpoint is at or above...", type: 'decimal', description: "Enter decimal dewpoint", 
                		required: false, submitOnChange: true)
				if (settings.aboveTemp || settings.dewAboveTemp) {
					input(name: 'aboveMode', title: 'Set thermostat mode to', type: 'enum', required: (!settings.aboveSetpoints), multiple: false, options:getThermostatModes(), submitOnChange: true)
                    if (settings.aboveMode == 'off') {
                    	paragraph "Note that Ecobee thermostats will still run fan circulation (if enabled) while the HVAC is in Off Mode"
                    }
                    input(name: 'aboveSetpoints', title: 'Change Program Setpoints', type: 'bool', required: (!settings.aboveMode), defaultValue: false, submitOnChange: true) 
                    if (settings.aboveSetpoints) {
                    	if (!settings.aboveProgram && (!settings.aboveHeatTemp || !settings.aboveCoolTemp)) paragraph "You must select the program to modify and at least one setpoint to change"
                    	input(name: 'aboveProgram', title: 'Change Setpoints for Program', type: 'enum', required: true, submitOnChange: true, multiple: false, 
                        		options:getEcobeePrograms())
                    	input(name: 'aboveHeatTemp', title: "Desired heating setpoint (${getHeatRange()})", type: 'decimal', description: 'Default = no change...', required: (!settings.aboveCoolTemp), 
                        		range: getHeatRange(), submitOnChange: true)
                        input(name: 'aboveCoolTemp', title: "Desired cooling setpoint (${getCoolRange()})", type: 'decimal', description: 'Default = no change...', required: (!settings.aboveHeatTemp), 
                        		range: getCoolRange(), submitOnChange: true)
                    }
				}
			}
            section(title: "Outdoor Temperature 'Below' Settings") {
            	input(name: "belowTemp", title: 'When the outdoor temperature is at or below...', type: 'decimal', description: "Enter decimal temperature (${settings.aboveTemp?'optional':'required'})", 
                		range: getThermostatRange(), required: !settings.aboveTemp, submitOnChange: true)
				if (settings.belowTemp) {
					input(name: 'belowMode', title: 'Set thermostat mode to', type: 'enum', required: (!settings.belowSetpoints), multiple: false, 
                    		options:getThermostatModes(), submitOnChange: true)
                    if (!(settings.aboveMode == 'off') && (settings.belowMode == 'off')) {
                    	paragraph "Note that Ecobee thermostats will still run fan circulation (if enabled) while the HVAC is in Off Mode"
                    }
                    input(name: 'belowSetpoints', title: 'Change Program Setpoints', type: 'bool', required: (!settings.belowMode), defaultValue: false, submitOnChange: true) 
                    if (settings.belowSetpoints) {
                    	if (!settings.belowProgram && (!settings.belowHeatTemp || !settings.belowCoolTemp)) paragraph "You must select the program to modify and at least one setpoint to change"
                    	input(name: 'belowProgram', title: 'Change Setpoints for Program', type: 'enum', required: true, submitOnChange: true, multiple: false, 
                        		options:getEcobeePrograms())
                    	input(name: 'belowHeatTemp', title: "Desired heating setpoint (${getHeatRange()})", type: 'decimal', description: 'Default = no change...', required: (!settings.belowCoolTemp), 
                        		range: getHeatRange(), submitOnChange: true)
                        input(name: 'belowCoolTemp', title: "Desired cooling setpoint (${getCoolRange()})", type: 'decimal', description: 'Default = no change...', required: (!settings.belowHeatTemp), 
                        		range: getCoolRange(), submitOnChange: true)
                    }
				}
                if (settings.belowTemp && (settings.belowMode == 'off')) {
                	input(name: 'dewBelowOverride', type: 'bool', title: 'Dewpoint overrides below temp Off Mode?', required: true, defaultValue: false, submitOnChange: true)
                	if (settings.dewBelowOverride) {
                    	input(name: 'dewBelowTemp', type: 'decimal', title: 'Override Off Mode when dew point is at or above...', description: "Enter decimal dew point", required: true, 
                        		submitOnChange: true)       
                	}
            	}
            }
			if ((settings.belowTemp && settings.aboveTemp) && (settings.belowTemp != settings.aboveTemp)) {
            	section(title: "Outdoor Temperature 'Between' Settings") {
					input(name: 'betweenMode', title: "When the outdoor temperature is between ${belowTemp}° and ${aboveTemp}°, set thermostat mode to (optional)", type: 'enum', 
                    		required: false, multiple: false, options:getThermostatModes(), submitOnChange: true)
            		input(name: 'betweenSetpoints', title: 'Change Program Setpoints', type: 'bool', required: false, defaultValue: false, submitOnChange: true) 
                    if (settings.betweenSetpoints) {
                    	if (!settings.betweenProgram && (!settings.betweenHeatTemp || !settings.betweenCoolTemp)) paragraph "You must select the program to modify and at least one setpoint to change"
                    	input(name: 'betweenProgram', title: 'Change Setpoints for Program', type: 'enum', required: true, submitOnChange: true, multiple: false, 
                        		options:getEcobeePrograms())
                    	input(name: 'betweenHeatTemp', title: 'Desired heating setpoint', type: 'decimal', description: 'Default = no change...', required: (!settings.betweenCoolTemp), 
                        		range: getHeatRange(), submitOnChange: true)
                        input(name: 'betweenCoolTemp', title: 'Desired cooling setpoint', type: 'decimal', description: 'Default = no change...', required: (!settings.betweenHeatTemp), 
                        		range: getCoolRange(), submitOnChange: true)
                    }
				}
            }
            section(title: "Indoor Temperature Settings (Optional)") {
            	if (getThermostatModes().contains('cool') && !settings.insideAuto) {
            		input(name: 'aboveCool', title: 'Set thermostat Mode to Cool if its indoor temperature is above its Cooling Setpoint (optional)?', type: 'bool', defaultValue: false, 
                    		submitOnChange: true)
                }
                if (getThermostatModes().contains('heat') && !settings.insideAuto) {
                	input(name: 'belowHeat', title: 'Set thermostat Mode to Heat if its indoor temperature is below its Heating Setpoint (optional)?', type: 'bool', defaultValue: false, 
                    		submitOnChange: true)
                }
                if (getThermostatModes().contains('auto') && !(settings.aboveCool || settings.belowHeat)) {
                	input(name: 'insideAuto', title: 'Set thermostat Mode to Auto if its indoor temperature is above or below its Setpoints (optional)?', type: 'bool', defaultValue: false, 
                    		submitOnChange: true)
                }
			}
			section(title: "Options") {
            	input(name: "theModes",type: "mode", title: "Change Thermostat Mode only when the Location Mode is", multiple: true, required: false)
				input(name: 'notify', type: 'bool', title: "Notify on Activations?", required: false, defaultValue: false, submitOnChange: true)
				paragraph isHE ? "A 'HelloHome' notification is always sent to the Location Event log whenever an action is taken\n" : "A notification is always sent to the Hello Home log whenever an action is taken\n"
        	}            
			if (settings.notify) {
				if (isST) {
					section("Notifications") {
						input(name: "phone", type: "string", title: "Phone number(s) for SMS, example +15556667777 (separate multiple with ; )", required: false, submitOnChange: true)
						input( name: 'pushNotify', type: 'bool', title: "Send Push notifications to everyone?", defaultValue: false, required: true, submitOnChange: true)
						input(name: "speak", type: "bool", title: "Speak the messages?", required: true, defaultValue: false, submitOnChange: true)
						if (settings.speak) {
							input(name: "speechDevices", type: "capability.speechSynthesis", required: (settings.musicDevices == null), title: "On these speech devices", multiple: true, submitOnChange: true)
							input(name: "musicDevices", type: "capability.musicPlayer", required: (settings.speechDevices == null), title: "On these music devices", multiple: true, submitOnChange: true)
							if (settings.musicDevices != null) input(name: "volume", type: "number", range: "0..100", title: "At this volume (%)", defaultValue: 50, required: true)
						}
						if (!settings.phone && !settings.pushNotify && !settings.speak) paragraph "WARNING: Notifications configured, but nowhere to send them!"
					}
				} else {		// isHE
					section("Use Notification Device(s)") {
						input(name: "notifiers", type: "capability.notification", title: "", required: ((settings.phone == null) && !settings.speak), multiple: true, 
							  description: "Select notification devices", submitOnChange: true)
						paragraph ""
					}
					section("Use SMS to Phone(s) (limit 10 messages per day)") {
						input(name: "phone", type: "string", title: "Phone number(s) for SMS, example +15556667777 (separate multiple with , )", 
							  required: ((settings.notifiers == null) && !settings.speak), submitOnChange: true)
						paragraph ""
					}
					section("Use Speech Device(s)") {
						input(name: "speak", type: "bool", title: "Speak messages?", required: true, defaultValue: false, submitOnChange: true)
						if (settings.speak) {
							input(name: "speechDevices", type: "capability.speechSynthesis", required: (settings.musicDevices == null), title: "On these speech devices", multiple: true, submitOnChange: true)
							input(name: "musicDevices", type: "capability.musicPlayer", required: (settings.speechDevices == null), title: "On these music devices", multiple: true, submitOnChange: true)
							input(name: "volume", type: "number", range: "0..100", title: "At this volume (%)", defaultValue: 50, required: true)
						}
						paragraph ""
					}
				}
			}
        }
        section(title: "Temporary Disable") {
        	input(name: "tempDisable", title: "Pause this Helper?", type: "bool", required: false, description: "", submitOnChange: true)                
		}
    	section (getVersionLabel()) {}
    }
}

void installed() {
	LOG("installed() entered", 3, "", 'trace')
    atomicState.aboveChanged = false
    atomicState.betweenChanged = false
    atomicState.belowChanged = false
    atomicState.dewpoint = null
    atomicState.humidity = null
	initialize()  
}

void uninstalled() {
	clearReservations()
}
void clearReservations() {
	thermostats?.each {
    	cancelReservation(getDeviceId(it.deviceNetworkId), 'modeOff' )
    }
}
void updated() {
	LOG("updated() with settings: ${settings}", 3, "", 'trace')
	unsubscribe()
    unschedule()
    atomicState.aboveChanged = false
    atomicState.betweenChanged = false
    atomicState.belowChanged = false
    atomicState.dewpoint = null
    atomicState.humidity = null
    initialize()
}

boolean initialize() {
	LOG("${getVersionLabel()}\nInitializing...", 2, "", 'info')
	updateMyLabel()
	
	if(settings.tempDisable) {
    	clearReservations()
    	LOG("Temporarily Paused", 2, null, "warn")
    	return true
    }
    
    if (settings.aboveCool || settings.belowHeat || settings.insideAuto) {
    	subscribe(thermostats, 'temperature', insideChangeHandler)
    }
    
    if (settings.aboveTemp || settings.belowTemp) {
    	subscribe(thermostats, 'thermostatMode', thermostatModeHandler)
    }
    def tempNow
    def gu = getTemperatureScale()
	switch( settings.tempSource) {
		case 'location':			
			// SmartThings Only
        	def WUname = (settings.latLon) ? 'getGPSTemp' : 'getZipTemp'
			if (settings.locFreq.toInteger() < 60) {
            	"runEvery${settings.locFreq}Minute${settings.locFreq!='1'?'s':''}"( "${WUname}" )
            } else {
            	def locHours = settings.locFreq.toInteger() / 60
                "runEvery${locHours}Hour${locHours!=1?'s':''}"( "${WUname}" )
            }
            def t = "${WUname}"()					// calls temperatureUpdate() & stores dewpoint
            if (t != null) tempNow = t as BigDecimal
			break;
		
		case 'sensors':
            if (settings.dewBelowOverride || (settings.dewAboveTemp != null)) {
            	if (settings.humidistat) { 
                	subscribe( settings.humidistat, 'relativeHumidity', humidityChangeHandler)
            	} else {
            		log.error "Dewpoint override(s) enabled, but no humidistat selected - initialization FAILED."
                	return false
                }
            }
            subscribe( settings.thermometer, 'temperature', tempChangeHandler)
            def latest = settings.thermometer.currentState("temperature")
			def unit = latest.unit
            def t 
            if (latest.numberValue != null) {
            	t = roundIt(latest.numberValue, (unit=='C'?2:1))
            	if (dewBelowOverride) {
                	latest = settings.humidistat.currentState('humidity')
            		if (latest.value != null) {
                    	def h = latest.numberValue
            			atomicState.humidity = h
                        LOG("Humidity is: ${h}%",3,null,'info')
                		def d = calculateDewpoint( t, h, unit )
            			atomicState.dewpoint = d
                        LOG("Dewpoint is: ${d}°${gu}",3,null,'info')
                   	}
                }
            	tempNow = t 
                temperatureUpdate(tempNow) 
            }
			break;
            
        case 'station':
        	if (settings.smartWeather) {
            	subscribe(settings.smartWeather, 'temperature', tempChangeHandler)
                def latest = settings.smartWeather.currentState('temperature')
                def t = latest.numberValue
                def unit = latest.unit
                if (t != null) {
                	t = latest.numberValue
                	if (dewBelowOverride) {
                		subscribe(settings.smartWeather, 'relativeHumidity', humidityChangeHandler)
                		latest = settings.smartWeather.currentState('relativeHumidity')
                		if (latest?.numberValue != null) {
                        	def h = roundIt(latest.numberValue, (unit=='C'?2:1))
                        	LOG("Humidity is: ${h}%",3,null,'info')
                			def d = calculateDewpoint( t, h, unit )
                            atomicState.dewpoint = d
                            LOG("Dewpoint is: ${d}°${gu}",3,null,'info')
                        }
                    }
                	tempNow = t 
                    temperatureUpdate(tempNow) 
                }
            } else if (settings.smartWeather2) {
            	def latest
                if (settings.dewBelowOverride) {
                	subscribe(settings.smartWeather2, 'dewpoint', dewpointChangeHandler)
                	latest = settings.smartWeather2.currentState('dewpoint')
                    if (latest?.numberValue != null) {
                    	def d = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                        atomicState.dewpoint = d
                        LOG("Dewpoint is: ${d}°${gu}",3,null,'info')
                    }
                }
            	subscribe(settings.smartWeather2, 'temperature', tempChangeHandler)
                latest = settings.smartWeather2.currentState('temperature')
            	if (latest?.numberValue != null) { 
                	tempNow = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                    temperatureUpdate(tempNow) 
                }
            } else if (settings.meteoWeather) {
            	def latest
                if (settings.dewBelowOverride) {
                	subscribe(settings.meteoWeather, 'dewpoint', dewpointChangeHandler)
                	latest = settings.meteoWeather.currentState('dewpoint')
                    if (latest?.numberValue != null) {
                    	def d = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                        atomicState.dewpoint = d
                        LOG("Dewpoint is: ${d}°${gu}",3,null,'info')
                    }
                }
            	subscribe(settings.meteoWeather, 'temperature', tempChangeHandler)
                latest = settings.meteoWeather.currentState('temperature')
            	if (latest?.numberValue != null) { 
                	tempNow = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                    temperatureUpdate(tempNow) 
                }
            } else if (settings.ambientWeather) {
            	def latest
                if (settings.dewBelowOverride) {
                	subscribe(settings.meteoWeather, 'dewPoint', dewpointChangeHandler)
                	latest = settings.meteoWeather.currentState('dewPoint')
                    if (latest?.numberValue != null) {
                    	def d = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                        atomicState.dewpoint = d
                        LOG("Dewpoint is: ${d}°${gu}",3,null,'info')
                    }
                }
            	subscribe(settings.meteoWeather, 'temperature', tempChangeHandler)
                latest = settings.meteoWeather.currentState('temperature')
            	if (latest?.numberValue != null) { 
                	tempNow = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                    temperatureUpdate(tempNow) 
                }
            }
        	break;
		
		case "ecobee":
			def theStat = []
            def latest
			theStat = settings.thermostats.size() == 1 ? settings.thermostats[0] : settings.tstatTemp
            if (dewBelowOverride) {
            	subscribe(theStat, 'weatherDewpoint', dewpointChangeHandler)
            	latest = theStat.currentState('weatherDewpoint')
            	if (latest?.numberValue != null) {
                	def d = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                	atomicState.dewpoint = d
                    LOG("Dewpoint is: ${d}°${gu}",3,null,'info')
                }
            }
            subscribe(theStat, 'weatherTemperature', tempChangeHandler)
            latest = theStat.currentState('weatherTemperature')
            if (latest?.numberValue != null) {
            	tempNow = roundIt(latest.numberValue, (latest.unit=='C'?2:1))
                temperatureUpdate(tempnow) 
            }
			break;
		
		case 'wunder':
			if (settings.pwsFreq.toInteger() < 60) {
            	"runEvery${settings.pwsFreq}Minute${settings.pwsFreq!='1'?'s':''}"( 'getPwsTemp' )
            } else {
            	def pwsHours = settings.pwsFreq.toInteger() / 60
                "runEvery${pwsHours}Hour${pwsHours!=1?'s':''}"( 'getPwsTemp' )
            }
            def t = getPwsTemp()					// calls temperatureUpdate() and updates atomicState.dewpoint
            if (t != null) tempNow = t as BigDecimal
			break;
	}
    atomicState.locModeEnabled = theModes ? theModes.contains(location.mode) : true
    if (tempNow) {
    	atomicState.temperature = tempNow
    	LOG("Initialization complete...current temperature is ${tempNow}°${gu}",2,null,'info')
        return true
    } else {
    	LOG("Initialization error...invalid temperature: ${tempNow}°${gu} - please check settings and retry", 2, null, 'error')
        return false
    }
}

def insideChangeHandler(evt) {
    def theTemp = evt.numberValue
    def newMode = null
    if (theTemp != null) {
    	theTemp = evt.numberValue
    	def coolSP = evt.device.currentValue('coolingSetpoint')
        if (coolSP != null) {
        	coolSP = coolSP.toBigDecimal()
        	if (theTemp > coolSP) {
            	if (settings.aboveCool) {
                	newMode = 'cool'
                } else if (settings.insideAuto && (evt.device.currentValue('thermostatMode') != 'cool')) {
                	newMode = 'auto'
                }
            }
        }
        if (newMode == null) {
       		def heatSP = evt.device.currentValue('heatingSetpoint')
            if (heatSP != null) {
            	heatSP = heatSP.toBigDecimal()
				if (theTemp < heatSP) {
                	if (settings.belowHeat) {
                    	newMode = 'heat'
                    } else if (settings.insideAuto && (evt.device.currentValue('thermostatMode') != 'heat')) {
                    	newMode = 'auto'
                    }
                }
            }
        }
        def okMode = theModes ? theModes.contains(location.mode) : true
        if (okMode) {
        	atomicState.locModeEnabled = true
            if (newMode != null) {
                def cMode = evt.device.currentValue('thermostatMode')
                if (cMode != newMode) {
                    def tid = getDeviceId(evt.device.deviceNetworkId)
                    if ((cMode == 'off') && anyReservations( tid, 'modeOff' )) {
                        // if ANYBODY (including me) has a reservation on this being off, I can't turn it back on
                        LOG("${evt.device.displayName} inside temp is ${theTemp}°${evt.unit}, but can't change to ${newMode} since ${getGuestList(tid,'offMode').toString()[1..-2]} have offMode reservations",2,null,'warn')
                        // Here's where we could subscribe to reservations and re-evaluate. For now, just wait for another inside Temp Change to occur
                    } else {
                        // not currently off or there are no modeOff reservations, change away!
                        cancelReservation(tid, 'modeOff' )
                        evt.device.setThermostatMode(newMode)
                        LOG("${evt.device.displayName} temp is ${theTemp}°${evt.unit}, changed thermostat to ${newMode} mode",3,null,'trace')
                        sendMessage("Thermostat ${evt.device.displayName} temperature is ${theTemp}°, so I changed it to ${newMode} mode")
                    }
                }
            }
        } else {
        	if (atomicState.locModeEnabled) {
                // Do we check for/cancel reservations?
                def tid = getDeviceId(evt.device.deviceNetworkId)
                cancelReservation(tid, 'modeOff')
                if (!anyReservations(tid, 'modeOff')) {
                    evt.device.setThermostatMode('auto')		// allow choice, keep reservation if off
                }
                atomicState.locModeEnabled = false
            }
        }
    }
}

def thermostatModeHandler(evt) {
	// if the mode changes but we didn't do it, reset the atomicState modes as appropriate
    if ((settings.aboveTemp || settings.dewAboveTemp) && (evt.value == settings.aboveMode) && !atomicState.aboveChanged) {
    	atomicState.belowChanged = false
        atomicState.betweenChanged = false
    }
    else if (settings.belowTemp && (evt.value == settings.belowMode) && !atomicState.belowChanged) {
    	atomicState.aboveChanged = false
        atomicState.betweenChanged = false
    }
    else if (settings.aboveTemp && settings.belowTemp && settings.betweenMode && (evt.value == settings.betweenMode) && !atomicState.betweenChanged) {
    	atomicState.aboveChanged = false
        atomicState.belowChanged = false
    }
    if (evt.value != 'off') cancelReservation( getDeviceId(evt.device.deviceNetworkId), 'modeOff' ) // we're not off anymore, give up the reservation
}

def tempChangeHandler(evt) {
    if (evt.numberValue != null) {
    	def t = roundIt(evt.numberValue, (evt.unit=='C'?2:1))
    	atomicState.temperature = t
        if (settings.dewBelowOverride || settings.dewAboveTemp) {
        	// We have to update the dewpoint every time the temperature (or humidity) changes
        	if (atomicState.humidity != null) {
            	// Somebody is updating atomicState.humidity, so we need to calculate the dewpoint
                // (Sources that provide dewpoint directly will not update atomicState.humidity)
            	if (settings.tempSource == 'sensors') {    
            		def latest = settings.humidistat.currentState('humidity')
            		if (latest.numberValue != null) {
                    	def h = latest.numberValue
            			atomicState.humidity = h
                        LOG("Humidity is: ${h}%",3,null,'info')
                		def d = calculateDewpoint( t, h, evt.unit )
            			atomicState.dewpoint = d
                        LOG("Dewpoint is: ${d}°${evt.unit}",3,null,'info')
                        runIn(2, atomicTempUpdater, [overwrite: true] )		// humidity might be updated also
                        return
                   	}
                } else if ((settings.tempSource == 'station') && settings.smartWeather) {
                	def latest = settings.smartWeather.currentState('relativeHumidity')
                    if (latest.numberValue != null) {
                    	h = latest.numberValue
                        LOG("Humidity is: ${h}%",3,null,'info')
                		def d = calculateDewpoint( t, h, unit )
                        atomicState.dewpoint = d
                        LOG("Dewpoint is: ${d}°${evt.unit}",3,null,'info')
                        runIn(2, atomicTempUpdater, [overwrite: true] )		// humidity might be updated also
                        return
                    }
                }
            } else {
            	runIn(2, atomicTempUpdater, [overwrite: true] )				// wait for dewpoint to be updated also
                return
            }
        }
        // Aren't doing dewpoint stuff, so we can just update the temp directly
    	temperatureUpdate( t )
    }
}   

def dewpointChangeHandler(evt) {
	if (evt.numberValue != null) {
    	def d = roundIt(evt.numberValue, (evt.unit=='C'?2:1))
    	atomicState.dewpoint = d
        LOG("Dewpoint is: ${d}°${evt.unit}",3,null,'info')
        runIn(2, atomicTempUpdater, [overwrite: true]) 		// wait for temp to be updated also
    }
}

def humidityChangeHandler(evt) {
	if (evt.numberValue != null) {
    	t = atomicState.temperature
        u = getTemperatureScale()
        atomicState.humidity = evt.numberValue
        LOG("Humidity is: ${evt.numberValue}%",3,null,'info')
    	def d = calculateDewpoint(t, evt.numberValue, u)
        atomicState.dewpoint = d
        LOG("Dewpoint is: ${d}°${getTemperatureScale()}",3,null,'info')
        runIn(2, atomicTempUpdater, [overwrite: true])
    }
}

def atomicTempUpdater() {
	temperatureUpdate( atomicState.temperature )
}

def temperatureUpdate( temp ) {
	if (temp != null) temperatureUpdate(temp as BigDecimal)
}
def temperatureUpdate( BigDecimal temp ) {
	def unit = getTemperatureScale()
    if (temp == null) {
    	LOG("Ignoring invalid temperature: ${temp}°${unit}", 2, null, 'warn')
        return false
    }
    
    temp = roundIt(temp, (unit=='C'?2:1))
    atomicState.temperature = temp
    LOG("Temperature is: ${temp}°${unit}",3,null,'info')
    
    def okMode = theModes ? theModes.contains(location.mode) : true
    if (okMode) {
    	atomicState.locModeEnabled = true
    } else {
    	if (atomicState.locModeEnabled) {
        	// release all the reservations and reset the mode
        	settings.thermostats.each { 
            	def tid = getDeviceId(it.deviceNetworkId)
            	// Do we check for/cancel reservations?
            	cancelReservation(tid, 'modeOff')
            	if (!anyReservations(tid, 'modeOff')) {
                	evt.device.setThermostatMode('auto')		// allow choice, keep reservation if off
            	}
            }
            atomicState.locModeEnabled = false
        }
        //LOG something
        return
    }
    
    def desiredMode = null
	if ( (settings.aboveTemp && (temp >= settings.aboveTemp)) || (settings.dewAboveTemp && (atomicState.dewpoint >= settings.dewAboveTemp))) {
    	if (!atomicState.aboveChanged) {
			desiredMode = settings.aboveMode
            if (settings.aboveSetpoints) {
            	changeSetpoints(settings.aboveProgram, settings.aboveHeatTemp, settings.aboveCoolTemp)
            }
            atomicState.aboveChanged = true
            atomicState.betweenChanged = false
            atomicState.belowChanged = false
        }
	} else if (settings.belowTemp && (temp <= settings.belowTemp)) {
    	if (!atomicState.belowChanged) {
        	// We haven't already changed to belowMode
        	if ( settings.belowSetpoints || (settings.belowMode && (settings.belowMode != 'off')) || !settings.dewBelowOverride || (settings.dewBelowTemp > atomicState.dewpoint)) {
            	// not turning HVAC off or aren't overriding off at this time
                desiredMode = settings.belowMode
                // TBD: Should we save the prior mode so we have something to return to???
                if (settings.belowSetpoints) {
            		changeSetpoints(settings.belowProgram, settings.belowHeatTemp, settings.belowCoolTemp)
            	}
                atomicState.aboveChanged = false
                atomicState.betweenChanged = false
                atomicState.belowChanged = true
            } else {
            	// not supposed to change the mode right now
                // belowMode is 'off', and dewBelowOverride is true, and current dewpoint is >= dewBelowTemp setting.
                // reset everything
                desiredMode = null
				atomicState.aboveChanged = false
                atomicState.betweenChanged = false
                atomicState.belowChanged = false
            }
        } else {
        	// We have prior changed to the belowMode - now we have to check if dewpoint is still below the limit
            if ((settings.belowMode == 'off') && settings.dewBelowOverride && (settings.dewBelowTemp <= atomicState.dewpoint)) {
            	// Uh-oh, the dewpoint has risen into the bad land
            	if (settings.betweenMode || settings.betweenSetpoints) {
                	// We have a between mode - let's change back to that
                	desiredMode = settings.betweenMode
                    if (settings.betweenSetpoints) {
            			changeSetpoints(settings.betweenProgram, settings.betweenHeatTemp, setting.betweenCoolTemp)
            		}
            		atomicState.aboveChanged = false
            		atomicState.betweenChanged = true
            		atomicState.belowChanged = false
                } else if (settings.aboveMode || settings.aboveSetpoints) {
                	// OK, no between mode. But we have an above mode - switch to that
                	desiredMode = settings.aboveMode
                    if (settings.aboveSetpoints) {
            			changeSetpoints(settings.aboveProgram, settings.aboveHeatTemp, setting.aboveCoolTemp)
            		}
                    atomicState.aboveChanged = true
                    atomicState.betweeChanged = false
                    atomicState.belowChanged = false
                }
            } else {
            	// No reason to change anything - 
            }
        }
	} else if ((settings.aboveTemp || (settings.dewAboveTemp && (atomicState.dewpoint < settings.dewAboveTemp))) && settings.belowTemp && settings.betweenMode) {
    	if (!atomicState.betweenChanged) {
			desiredMode = settings.betweenMode
            if (settings.betweenSetpoints) {
            	changeSetpoints(settings.betweenProgram, settings.betweenHeatTemp, setting.betweenCoolTemp)
            }
            atomicState.aboveChanged = false
            atomicState.betweenChanged = true
            atomicState.belowChanged = false
        }
	}
	if (desiredMode != null) {
    	String changeNames = ""
        String sameNames = ""
		settings.thermostats.each { 
        	def cMode = it.currentValue('thermostatMode')
            def tid = getDeviceId(it.deviceNetworkId)
			if ( cMode != desiredMode) {
            	if (desiredMode == 'off') {
                	makeReservation(tid, 'modeOff')
                    it.setThermostatMode( 'off' )
                } else {
                	// Desired mode IS NOT 'off'
                	if (cMode == 'off') {
                    	cancelReservation(tid,'modeOff')
                    	if (countReservations(tid, 'modeOff') == 0) {
                    		// nobody else has a reservation on modeOff
							it.setThermostatMode(desiredMode)
                			changeNames += changeNames ? ", ${it.displayName}" : it.displayName
						} else {
                    		// somebody else still has a 'modeOff' reservation so we can't turn it on
                            def msg = "The temperature is ${temp}°${unit}, but I can't change ${it.displayName} to ${desiredMode} Mode because ${getGuestList(tid,'modeOff').toString()[1..-2]} hold 'modeOff' reservations"
                            LOG(msg ,2,null,'warn')
                            sendMessage(msg)
                            // here's where we COULD subscribe to the reservations to see when we can turn it back on. For now, let's just let whomever is last deal with it
                    	}
                    } else {
                    	// Not off currently, so we can change freely
                        cancelReservation(tid, 'modeOff')	// just in case
                    	it.setThermostatMode(desiredMode)
                    }
                }
            } else {
            	// already running the mode we want
            	(desireMode == 'off') ? makeReservation(tid, 'modeOff') : cancelReservation(tid, 'modeOff')
	            sameNames += sameNames ? ", ${it.displayName}" : it.displayName
            }
		}
        def multi=0
        if (changeNames) {
        	LOG("Temp is ${temp}°${unit}, changed ${changeNames} to ${desiredMode} mode",3,null,'trace')
        	sendMessage("The temperature is ${temp}°${unit}, so I changed thermostat${changeNames.size() > 1?'s':''} ${changeNames} to ${desiredMode} mode")
        }
        if (sameNames) LOG("Temp is ${temp}°${unit}, ${sameNames} already in ${desiredMode} mode",3,null,'info')
	}
}

private def changeSetpoints( program, heatTemp, coolTemp ) {
	def unit = getTemperatureScale()
	settings.thermostats.each { stat ->
    	LOG("Setting ${stat.displayName} '${program}' heatingSetpoint to ${heatTemp}°${unit}, coolingSetpoint to ${coolTemp}°${unit}",2,null,'info')
    	stat.setProgramSetpoints( program, heatTemp, coolTemp )
    }
}

private def getZipTemp() {
	return getTwcTemp('zip')
}

private def getGPSTemp() {
	return getTwcTemp('gps')
}

private def getPwsTemp() {
	return getWUTemp('pws')
}

// SmartThings-only
private def getTwcTemp(type) {
	def isMetric = (getTemperatureScale() == "C")
	def source = (type == 'zip') ? settings.zipCode : ((type == 'gps')?"${location.latitude},${location.longitude}":null)
    
    def twcConditions = [:]
    try {
    	twcConditions = getTwcConditions(source)
    } catch (e) {
    	LOG("Error getting TWC Conditions: ${e}",1,null,'error')
        return null
    }
    if (twcConditions) {
    	LOG("Parsing TWC data",3,null,'info')
        def tempNow
    	def dewpointNow = -999.0
    	tempNow = twcConditions.temperature
        dewpointNow = twcConditions.temperatureDewPoint

        if (tempNow != null) {
        	if (dewpointNow != -999.0) {
        		atomicState.dewpoint = dewpointNow
            } else {
            	def hum = twcConditions.relativeHumidity
                if ((hum != null) && hum.contains('%')) hum = (hum-'%') as Integer		// strip off the trailing '%' sign
                if (hum.toString().isNumber()) {
                	dewpointNow = calculateDewpoint( tempNow, hum, (isMetric?'C':'F'))
                }
                atomicState.dewpoint = dewpointNow
            }
            LOG("Dewpoint is: ${dewpointNow}°${isMetric?'C':'F'}",2,null,'info')
        	temperatureUpdate(tempNow)
            return tempNow
        } else {
        	LOG("Invalid temp returned ${newTemp}, ignoring...",2,null,'warn')
            return null
        }
    }
    LOG("Current conditions unavailable",1,null,'error')
    return null
}
// SmartThings only - deprecated
private def getWUTemp(type) {
	def isMetric = (getTemperatureScale() == "C")
    def tempNow
    def dewpointNow
    def source = (type == 'zip') ? settings.zipCode : ((type == 'gps')?"${location.latitude},${location.longitude}":settings.stationID)
	Map wdata = getWeatherFeature('conditions', source)
    LOG("Requesting WU data for source: ${source}",3,null,'info')
    if (wdata && wdata.response) {
    	//LOG("conditions: ${wdata.response}",4,null,'trace')
		if (wdata.response.containsKey('error')) {
        	if (wdata.response.error.type != 'invalidfeature') {
    			LOG("Please check ${type=='zip'?'ZIPcode':((type=='gps')?'Location Lat/Lon':'WU Station')} setting, error:\n${wdata.response.error.type}: ${wdata.response.error.description}" ,1,null,'error')
        		return null
            } 
            else {
            	LOG("Error requesting weather:\n${wdata.response.error}",2,null,'warn')
            	return null
            }
		}
    }
    else {
    	LOG("Please check ZIPcode, Lat/Lon, or PWS setting, weather returned: null",2,null,'warn')
    	return null
    }
    if (wdata.current_observation) { 
    	LOG("Parsing WU data for station: ${wdata.current_observation.station_id}",3,null,'info')
    	if (!isMetric) {
			if (wdata.current_observation.temp_f?.isNumber()) tempNow = wdata.current_observation.temp_f.toBigDecimal()
            if (wdata.current_observation.dewpoint_f?.isNumber()) dewpointNow = wdata.current_observation.dewpoint_f.toBigDecimal()
        } else {
        	if (wdata.current_observation.temp_c?.isNumber()) tempNow = wdata.current_observation.temp_c.toBigDecimal()
            if (wdata.current_observation.dewpoint_c?.isNumber()) dewpointNow = wdata.current_observation.dewpoint_c.toBigDecimal()
        }
        if (tempNow?.isNumber()) {
        	if (dewpointNow != -999.0) {
        		atomicState.dewpoint = dewpointNow
            } else {
            	def hum = wdata.current_observation.relative_humidity
                if (hum && hum.contains('%')) hum = (hum-'%').toInteger()		// strip off the trailing '%' sign
                if (hum.isNumber()) {
                	dewpointNow = calculateDewpoint( tempNow, hum, (isMetric?'C':'F'))
                }
                atomicState.dewpoint = dewpointNow
            }
            LOG("Dewpoint is: ${dewpointNow}°${isMetric?'C':'F'}",2,null,'info')
        	temperatureUpdate(tempNow)
            return tempNow
        } else {
        	LOG("Invalid temp returned ${newTemp}, ignoring...",2,null,'warn')
            return null
        }
    }
    LOG("Current conditions unavailable",1,null,'error')
    return null
}

// Calculate a close approximation of Dewpoint based on Temp, Relative Humidity (need Units - algorithm only works for C values)
def calculateDewpoint( temp, rh, units) {
	def t = ((units == 'C') ? temp : (temp-32)/1.8) as BigDecimal
	def dpC = 243.04*(Math.log(rh/100.0)+((17.625*t)/(243.04+t)))/(17.625-Math.log(rh/100.0)-((17.625*t)/(243.04+t)))
    return (units == 'C') ? roundIt(dpC, 2) : roundIt(((dpC*1.8)+32), 1)
}
private roundIt( value, decimals=0 ) {
	return (value == null) ? null : value.toBigDecimal().setScale(decimals, BigDecimal.ROUND_HALF_UP) 
}
private roundIt( BigDecimal value, decimals=0) {
    return (value == null) ? null : value.setScale(decimals, BigDecimal.ROUND_HALF_UP) 
}

// return all the modes that ALL thermostats support
def getThermostatModes() {
	def theModes = []
    
    settings.thermostats?.each { stat ->
    	if (theModes == []) {
        	theModes = stat.currentValue('supportedThermostatModes')[1..-2].tokenize(", ")
        } else {
        	theModes = theModes.intersect(stat.currentValue('supportedThermostatModes')[1..-2].tokenize(", "))
        }   
    }
    return theModes.sort(false)
}

// get the combined set of Ecobee Programs applicable for these thermostats
private def getEcobeePrograms() {
	def programs = ['Away', 'Home', 'Sleep'] 

	if (settings.thermostats?.size() > 0) {
		settings.thermostats.each { stat ->
			def pl = stat.currentValue('programsList')
            if (pl) programs = programs.intersect(new JsonSlurper().parseText(pl))
        }
	} 
    LOG("getEcobeePrograms: returning ${programs}", 4)
    return programs.sort(false)
}

// return the external temperature range
def getThermostatRange() {
	def low
    def high
	if (getTemperatureScale() == "C") {
    	low = -20.0
        high = 40.0
    } else {
    	low = -5.0
		high = 105.0
    }
	return "${low}..${high}"
}

def getHeatRange() {
	def low
    def high
    settings.thermostats.each { stat ->
    	def lo
        def hi
        def setp = stat.currentValue('heatRangeLow')
        lo = lo ? ((setp < lo) ? setp : lo) : setp
        setp = stat.currentValue('heatRangeHigh')
        hi = hi ? ((setp > hi) ? setp : hi) : setp
        // if there are multiple stats, we need to find the range that ALL stats can support
        low = low ? ((lo > low) ? lo : low) : lo
        high = high ? ((hi < high) ? hi : high) : hi
    }
    return "${roundIt(low-0.5,0)}..${roundIt(high-0.5,0)}"
}

def getCoolRange() {
	def low
    def high
    settings.thermostats.each { stat ->
    	def lo
        def hi
        def setp = stat.currentValue('coolRangeLow')
        lo = lo ? ((setp < lo) ? setp : lo) : setp
        setp = stat.currentValue('coolRangeHigh')
        hi = hi ? ((setp > hi) ? setp : hi) : setp
        // if there are multiple stats, we need to find the range that ALL stats can support
        low = low ? ((lo > low) ? lo : low) : lo
        high = high ? ((hi < high) ? hi : high) : hi
    }
    return "${roundIt(low-0.5,0)}..${roundIt(high-0.5,0)}"
}

private String getZIPcode() {
	return location.zipCode ?: ""
}

private String getPWSID() {
	String PWSID = location.zipCode
	log.debug "Location ZIP Code ${PWSID}"
	// find the nearest PWS to the hub's geo location
	String geoLocation = location.zipCode
	// use coordinates, if available
	if (location.latitude && location.longitude) geoLocation = "${location.latitude},${location.longitude}"
    log.debug "Geolocation: ${geoLocation}"
    Map wdata = getWeatherFeature('geolookup', geoLocation)
    if (wdata && wdata.response && !wdata.response.containsKey('error')) {	// if we get good data
    	if (wdata.response.features.containsKey('geolookup') && (wdata.response.features.geolookup.toInteger() == 1) && wdata.location) {
        	//log.debug "wdata ${wdata.location.nearby_weather_stations.pws}"
            log.debug "wdata ${wdata.location.nearby_weather_stations}"
    		if (wdata.location.nearby_weather_stations?.pws?.station[0]?.id) PWSID = 'pws:' + wdata.location.nearby_weather_stations.pws.station[0].id
            else if (wdata.location.nearby_weather_stations?.airport?.station[0]?.icao) PWSID = wdata.location.nearby_weather_stations.airport.station[0].icao
    	}
    	else log.debug "bad response"
    }
    else log.debug "null or error"

	log.debug "Nearest PWS ${PWSID}"
	return PWSID
}

private def getDeviceId(networkId) {
	// def deviceId = networkId.split(/\./).last()	
    // LOG("getDeviceId() returning ${deviceId}", 4, null, 'trace')
    // return deviceId
    return networkId.split(/\./).last()
}

// Reservation Management Functions - Now implemented in Ecobee Suite Manager
void makeReservation(String tid, String type='modeOff' ) {
	parent.makeReservation( tid, app.id as String, type )
}
// Cancel my reservation
void cancelReservation(String tid, String type='modeOff') {
	log.debug "cancel ${tid}, ${type}"
	parent.cancelReservation( tid, app.id as String, type )
}
// Do I have a reservation?
Boolean haveReservation(String tid, String type='modeOff') {
	return parent.haveReservation( tid, app.id as String, type )
}
// Do any Apps have reservations?
Boolean anyReservations(String tid, String type='modeOff') {
	return parent.anyReservations( tid, type )
}
// How many apps have reservations?
Integer countReservations(String tid, String type='modeOff') {
	return parent.countReservations( tid, type )
}
// Get the list of app IDs that have reservations
List getReservations(String tid, String type='modeOff') {
	return parent.getReservations( tid, type )
}
// Get the list of app Names that have reservations
List getGuestList(String tid, String type='modeOff') {
	return parent.getGuestList( tid, type )
}

// Helper Functions
private def LOG(message, level=3, child=null, logType="debug", event=true, displayEvent=true) {
	def msg = app.label + ': ' + message
	if (logType == null) logType = 'debug'
	parent.LOG(msg, level, null, logType, event, displayEvent)
    log."${logType}" message
}

private def sendMessage(notificationMessage) {
	LOG("Notification Message (notify=${notify}): ${notificationMessage}", 2, null, "trace")
    if (settings.notify) {
        String msg = "${app.label} at ${location.name}: " + notificationMessage		// for those that have multiple locations, tell them where we are
		if (isST) {
			if (settings.phone) { // check that the user did select a phone number
				if ( settings.phone.indexOf(";") > 0){
					def phones = settings.phone.split(";")
					for ( def i = 0; i < phones.size(); i++) {
						LOG("Sending SMS ${i+1} to ${phones[i]}", 3, null, 'info')
						sendSmsMessage(phones[i], msg)				// Only to SMS contact
					}
				} else {
					LOG("Sending SMS to ${settings.phone}", 3, null, 'info')
					sendSmsMessage(settings.phone, msg)						// Only to SMS contact
				}
			} 
			if (settings.pushNotify) {
				LOG("Sending Push to everyone", 3, null, 'warn')
				sendPushMessage(msg)								// Push to everyone
			}
			if (settings.speak) {
				if (settings.speechDevices != null) {
					settings.speechDevices.each {
						it.speak( "From " + msg )
					}
				}
				if (settings.musicDevices != null) {
					settings.musicDevices.each {
						it.setLevel( settings.volume )
						it.playText( "From " + msg )
					}
				}
			}
		} else {		// isHE
			if (settings.notifiers != null) {
				settings.notifiers.each {							// Use notification devices on Hubitat
					it.deviceNotification(msg)
				}
			}
			if (settings.phone != null) {
				if ( settings.phone.indexOf(",") > 0){
					def phones = phone.split(",")
					for ( def i = 0; i < phones.size(); i++) {
						LOG("Sending SMS ${i+1} to ${phones[i]}", 3, null, 'info')
						sendSmsMessage(phones[i], msg)				// Only to SMS contact
					}
				} else {
					LOG("Sending SMS to ${settings.phone}", 3, null, 'info')
					sendSmsMessage(settings.phone, msg)						// Only to SMS contact
				}
			}
			if (settings.speak) {
				if (settings.speechDevices != null) {
					settings.speechDevices.each {
						it.speak( "From " + msg )
					}
				}
				if (settings.musicDevices != null) {
					settings.musicDevices.each {
						it.setLevel( settings.volume )
						it.playText( "From " + msg )
					}
				}
			}
			
		}
    }
	// Always send to Hello Home / Location Event log
	if (isST) { 
		sendNotificationEvent( notificationMessage )					
	} else {
		sendLocationEvent(name: "HelloHome", descriptionText: notificationMessage, value: app.label, type: 'APP_NOTIFICATION')
	}
}

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
