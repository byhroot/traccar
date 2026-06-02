/*
 * Copyright 2021 Anton Tananaev (anton@traccar.org)
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

import io.netty.channel.Channel;
import org.traccar.Protocol;
import org.traccar.StringProtocolEncoder;
import org.traccar.helper.Checksum;
import org.traccar.model.Command;

public class StartekProtocolEncoder extends StringProtocolEncoder {

    public StartekProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected String formatCommand(Command command, String format, String... keys) {
        String uniqueId = getUniqueId(command.getDeviceId());
        String payload = super.formatCommand(command, format, keys);
        int length = 1 + uniqueId.length() + 1 + payload.length();
        String sentence = "$$:" + length + "," + uniqueId + "," + payload;
        return sentence + Checksum.sum(sentence) + "\r\n";
    }

    @Override
    protected Object encodeCommand(Channel channel, Command command) {

        return switch (command.getType()) {

            // Mevcut komutlar
            case Command.TYPE_CUSTOM ->
                formatCommand(command, "%s", Command.KEY_DATA);
            case Command.TYPE_OUTPUT_CONTROL ->
                formatCommand(command, "900,%s,%s", Command.KEY_INDEX, Command.KEY_DATA);
            case Command.TYPE_ENGINE_STOP ->
                formatCommand(command, "900,1,1");
            case Command.TYPE_ENGINE_RESUME ->
                formatCommand(command, "900,1,0");

            // Bağlantı ayarları
            case Command.TYPE_SET_CONNECTION ->
                formatCommand(command, "100,1,%s,%s", Command.KEY_SERVER, Command.KEY_PORT);
            case Command.TYPE_SEND_USSD ->
                formatCommand(command, "804,%s", Command.KEY_DATA);

            // Uyku modu (124 komutu)
            case Command.TYPE_MODE_DEEP_OFF ->
                formatCommand(command, "124,0");
            case Command.TYPE_MODE_POWER_SAVING ->
                formatCommand(command, "124,1");
            case Command.TYPE_MODE_DEEP_SLEEP ->
                formatCommand(command, "124,2");

            // Armed/Disarmed (152 komutu)
            case Command.TYPE_ALARM_ARM ->
                formatCommand(command, "152,1");
            case Command.TYPE_ALARM_DISARM ->
                formatCommand(command, "152,0");

            // Reboot - factory reset yok protokolde, custom ile yapılabilir
            case Command.TYPE_REBOOT_DEVICE ->
                formatCommand(command, "901");

            case Command.TYPE_ALARM_ACCELERATION -> {

                int acceleration = command.getInteger(Command.KEY_ACCELERATION);

                if (acceleration < 0) {
                    acceleration = 0;
                }

                if (acceleration > 5000) {
                    acceleration = 5000;
                }

                yield formatCommand(command, "128," + acceleration);
            }

            case Command.TYPE_ALARM_DECELERATION -> {

                int deceleration = command.getInteger(Command.KEY_DECELERATION);

                if (deceleration < 0) {
                    deceleration = 0;
                }

                if (deceleration > 5000) {
                    deceleration = 5000;
                }

                yield formatCommand(command, "129," + deceleration);
            }
            default -> null;
            // Harsh turning alarm (130)
            case Command.TYPE_ALARM_HARSH_TURNING -> {

                int value = command.getInteger(Command.KEY_VALUE);

                // 0-32
                if (value < 0) {
                    value = 0;
                }

                if (value > 32) {
                    value = 32;
                }

                yield formatCommand(command, "130," + value);
            }

            // Impact alarm (131)
            case Command.TYPE_ALARM_IMPACT -> {

                int value = command.getInteger(Command.KEY_VALUE);

                // 0-10
                if (value < 0) {
                    value = 0;
                }

                if (value > 10) {
                    value = 10;
                }

                yield formatCommand(command, "131," + value);
            }

            // Vibration / Tow alarm (132)
            case Command.TYPE_ALARM_VIBRATION -> {

                int time = command.getInteger(Command.KEY_TIME);
                int mode = command.getInteger(Command.KEY_MODE);

                // time: 0-255
                if (time < 0) {
                    time = 0;
                }

                if (time > 255) {
                    time = 255;
                }

                // mode: 0-1
                if (mode < 0 || mode > 1) {
                    mode = 0;
                }

                // Eğer mode gönderilmezse sadece 132,time
                if (!command.getAttributes().containsKey(Command.KEY_MODE)) {
                    yield formatCommand(command, "132," + time);
                }

                yield formatCommand(command, "132," + time + "," + mode);
            }
            // Idling alarm (133)
            case Command.TYPE_ALARM_IDLING -> {

                int time = command.getInteger(Command.KEY_TIME);

                // 0-255 minute
                if (time < 0) {
                    time = 0;
                }

                if (time > 255) {
                    time = 255;
                }

                yield formatCommand(command, "133," + time);
            }

            // Fatigue driving alarm (134)
            case Command.TYPE_ALARM_FATIGUE_DRIVING -> {

                int fatigueTime = command.getInteger(Command.KEY_FATIGUE_TIME);
                int restTime = command.getInteger(Command.KEY_REST_TIME);
                int totalDriveTime = command.getInteger(Command.KEY_TOTAL_DRIVE_TIME);

                // validation
                fatigueTime = Math.max(0, Math.min(255, fatigueTime));
                restTime = Math.max(0, Math.min(255, restTime));
                totalDriveTime = Math.max(0, Math.min(1440, totalDriveTime));

                yield formatCommand(
                        command,
                        "134," + fatigueTime + "," + restTime + "," + totalDriveTime);
            }
            // Vibration sensitivity (151)
            case Command.TYPE_ALARM_VIBRATION_SENSITIVITY -> {

                int sensitivity = command.getInteger(Command.KEY_SENSITIVITY);

                // 3-255
                if (sensitivity < 3) {
                    sensitivity = 3;
                }

                if (sensitivity > 255) {
                    sensitivity = 255;
                }

                yield formatCommand(command, "151," + sensitivity);
            }

            // GSM jamming alarm (153)
            case Command.TYPE_ALARM_GSM_JAMMING -> {

                int time = command.getInteger(Command.KEY_TIME);

                // 0-255 sec
                if (time < 0) {
                    time = 0;
                }

                if (time > 255) {
                    time = 255;
                }

                yield formatCommand(command, "153," + time);
            }

            // ACC Off no GNSS update (155)
            case Command.TYPE_ACC_OFF_NO_POSITION -> {

                boolean enable = command.getBoolean(Command.KEY_ENABLE);

                yield formatCommand(command, "155," + (enable ? 1 : 0));
            }

            // SMS interval (200)
            case Command.TYPE_SMS_INTERVAL -> {

                int normalTime = command.getInteger(Command.KEY_NORMAL_TIME);
                int accOffTime = command.getInteger(Command.KEY_ACC_OFF_TIME);
                int stoppingTime = command.getInteger(Command.KEY_STOPPING_TIME);

                normalTime = Math.max(0, Math.min(65535, normalTime));
                accOffTime = Math.max(0, Math.min(65535, accOffTime));
                stoppingTime = Math.max(0, Math.min(65535, stoppingTime));

                yield formatCommand(
                        command,
                        "200," + normalTime + "," + accOffTime + "," + stoppingTime);
            }
            case Command.TYPE_SET_AGPS -> {

                boolean enable = command.getBoolean(Command.KEY_ENABLE);

                yield formatCommand(command, "158," + (enable ? 1 : 0));
            }
        };
    }

}
