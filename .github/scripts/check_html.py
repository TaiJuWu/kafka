# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


from bs4 import BeautifulSoup
import glob

def is_valid_HTML_tag(html_string_to_check: str) -> bool:
    soup = BeautifulSoup(html_string_to_check, 'html.parser')
    return html_string_to_check == str(soup)

if __name__ == "__main__":
    htmls = glob.glob(r'*.html')
    for html in htmls:
        with open(html, "r") as fp:
            content = fp.read()
            if not is_valid_HTML_tag(content):
                exit(1)
