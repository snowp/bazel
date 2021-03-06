// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.aapt2;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A library generated by aapt2. */
public class StaticLibrary {

  private final Path library;
  private final Optional<Path> rTxt;

  private static final Function<StaticLibrary, String> LIBRARY_TO_STRING =
      input -> input.library.toString();

  private StaticLibrary(Path library, Optional<Path> rTxt) {
    this.library = library;
    this.rTxt = rTxt;
  }

  public static StaticLibrary from(Path library) {
    return new StaticLibrary(library, Optional.empty());
  }

  public static StaticLibrary from(Path library, Path rTxt) {
    return new StaticLibrary(library, Optional.of(rTxt));
  }

  public StaticLibrary copyLibraryTo(Path target) throws IOException {
    return new StaticLibrary(Files.copy(library, target), rTxt);
  }

  public StaticLibrary copyRTxtTo(final Path target) {
    return new StaticLibrary(
        library,
        rTxt.map(
            input -> {
              try {
                return Files.copy(input, target);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            }));
  }

  public static Collection<String> toPathStrings(List<StaticLibrary> libraries) {
    return Lists.transform(libraries, LIBRARY_TO_STRING);
  }
}
