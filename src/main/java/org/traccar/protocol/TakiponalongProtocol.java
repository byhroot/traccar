/*
 * Copyright 2015 - 2019 Anton Tananaev (anton@traccar.org)
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

import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.model.Command;

import jakarta.inject.Inject;

public class TakiponalongProtocol extends BaseProtocol {

    @Inject
    public TakiponalongProtocol(Config config) {
        setSupportedDataCommands(
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME,
                Command.TYPE_ALARM_VIBRATIONON,
                Command.TYPE_ALARM_VIBRATIONOFF,
                Command.TYPE_ALARM_VIBRATIONLEVEL,
                Command.TYPE_CUSTOM);
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new TakiponalongFrameDecoder());
                pipeline.addLast(new TakiponalongProtocolEncoder(TakiponalongProtocol.this));
                pipeline.addLast(new TakiponalongProtocolDecoder(TakiponalongProtocol.this));
            }
        });
    }

}
