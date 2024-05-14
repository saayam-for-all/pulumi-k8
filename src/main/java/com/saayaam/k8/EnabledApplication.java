package com.saayaam.k8;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

public record EnabledApplication(String name, String tag) {
  private static final String NAME = "name";
  private static final String TAG = "tag";

  static ImmutableList<EnabledApplication> read(String yamlString) {
    if (Strings.isNullOrEmpty(yamlString)) {
      return ImmutableList.of();
    }

    Yaml yaml = new Yaml();
    List<Map<String, String>> applications = yaml.loadAs(yamlString, List.class);
    return applications
        .stream()
        .map(m -> new EnabledApplication(m.get(NAME), m.get(TAG)))
        .collect(ImmutableList.toImmutableList());
  }
}
