/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.rosco.providers.google

import com.netflix.spinnaker.rosco.api.Bake
import com.netflix.spinnaker.rosco.api.BakeRequest
import com.netflix.spinnaker.rosco.providers.CloudProviderBakeHandler
import com.netflix.spinnaker.rosco.providers.google.config.RoscoGoogleConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
public class GCEBakeHandler extends CloudProviderBakeHandler {

  private static final String BUILDER_TYPE = "googlecompute"
  private static final String IMAGE_NAME_TOKEN = "googlecompute: A disk image was created:"

  @Autowired
  RoscoGoogleConfiguration.GCEBakeryDefaults gceBakeryDefaults

  @Autowired
  RoscoGoogleConfiguration.GoogleConfigurationProperties googleConfigurationProperties

  @Override
  String produceBakeKey(String region, BakeRequest bakeRequest) {
    // TODO(duftler): Work through definition of uniqueness.
    bakeRequest.with {
      return "bake:$cloud_provider_type:$base_os:$package_name"
    }
  }

  @Override
  def findVirtualizationSettings(String region, BakeRequest bakeRequest) {
    def virtualizationSettings = gceBakeryDefaults?.operatingSystemVirtualizationSettings.find {
      it.os == bakeRequest.base_os
    }?.virtualizationSettings

    if (!virtualizationSettings) {
      throw new IllegalArgumentException("No virtualization settings found for '$bakeRequest.base_os'.")
    }

    return virtualizationSettings
  }

  @Override
  Map buildParameterMap(String region, def gceVirtualizationSettings, String imageName) {
    RoscoGoogleConfiguration.ManagedGoogleAccount managedGoogleAccount = googleConfigurationProperties?.accounts?.getAt(0)

    if (!managedGoogleAccount) {
      throw new IllegalArgumentException("No Google account specified for bakery.")
    }

    def parameterMap = [
      gce_project_id:   managedGoogleAccount.project,
      gce_zone:         gceBakeryDefaults.zone,
      gce_source_image: gceVirtualizationSettings.sourceImage,
      gce_target_image: imageName
    ]

    if (managedGoogleAccount.jsonPath) {
      parameterMap.gce_account_file = managedGoogleAccount.jsonPath
    }

    return parameterMap
  }

  @Override
  String getTemplateFileName() {
    return gceBakeryDefaults.templateFile
  }

  @Override
  boolean isProducerOf(String logsContentFirstLine) {
    logsContentFirstLine =~ BUILDER_TYPE
  }

  @Override
  Bake scrapeCompletedBakeResults(String region, String bakeId, String logsContent) {
    String imageName

    // TODO(duftler): Presently scraping the logs for the image name. Would be better to not be reliant on the log
    // format not changing. Resolve this by storing bake details in redis.
    logsContent.eachLine { String line ->
      if (line =~ IMAGE_NAME_TOKEN) {
        imageName = line.split(" ").last()
      }
    }

    return new Bake(id: bakeId, image_name: imageName)
  }
}