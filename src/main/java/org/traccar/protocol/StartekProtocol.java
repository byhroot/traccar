/*
 * Copyright 2021 - 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.model.Command;

import jakarta.inject.Inject;

public class StartekProtocol extends BaseProtocol {

    @Inject
    public StartekProtocol(Config config) {
        setSupportedDataCommands(
                Command.TYPE_CUSTOM,
                Command.TYPE_OUTPUT_CONTROL,
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME,
                Command.TYPE_SET_CONNECTION,
                Command.TYPE_SEND_USSD,

                // Power modes
                Command.TYPE_MODE_DEEP_OFF,
                Command.TYPE_MODE_POWER_SAVING,
                Command.TYPE_MODE_DEEP_SLEEP,

                // Arming
                Command.TYPE_ALARM_ARM,
                Command.TYPE_ALARM_DISARM,

                // Motion / driving alarms
                Command.TYPE_ALARM_ACCELERATION,
                Command.TYPE_ALARM_DECELERATION,
                Command.TYPE_ALARM_HARSH_TURNING,
                Command.TYPE_ALARM_IMPACT,
                Command.TYPE_ALARM_VIBRATION,
                Command.TYPE_ALARM_VIBRATION_SENSITIVITY,
                Command.TYPE_ALARM_IDLING,
                Command.TYPE_ALARM_FATIGUE_DRIVING,
                Command.TYPE_ALARM_GSM_JAMMING,

                // Position / AGPS
                Command.TYPE_ACC_OFF_NO_POSITION,
                Command.TYPE_SET_AGPS,

                // Reporting
                Command.TYPE_SMS_INTERVAL,

                // Device
                Command.TYPE_REBOOT_DEVICE);
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new StartekFrameDecoder());
                pipeline.addLast(new StringEncoder());
                pipeline.addLast(new StringDecoder());
                pipeline.addLast(new StartekProtocolEncoder(StartekProtocol.this));
                pipeline.addLast(new StartekProtocolDecoder(StartekProtocol.this));
            }
        });
    }

}
