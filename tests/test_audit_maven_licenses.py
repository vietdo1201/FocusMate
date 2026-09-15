# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0

import unittest
import xml.etree.ElementTree as ET

from tools.audit_maven_licenses import parent_coordinate, pom_licenses, spdx_for_license


class MavenLicenseAuditTest(unittest.TestCase):
    def test_maps_only_unambiguous_supported_license_names(self) -> None:
        self.assertEqual(
            "Apache-2.0",
            spdx_for_license("The Apache Software License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
        )
        self.assertEqual("MIT", spdx_for_license("The MIT License", "https://opensource.org/licenses/MIT"))
        self.assertIsNone(spdx_for_license("Public Domain", "https://example.invalid/license"))

    def test_reads_namespaced_pom_license(self) -> None:
        root = ET.fromstring(
            """<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license>
            <name>BSD 3-Clause</name><url>https://opensource.org/licenses/BSD-3-Clause</url>
            </license></licenses></project>"""
        )
        self.assertEqual(
            [("BSD 3-Clause", "https://opensource.org/licenses/BSD-3-Clause")],
            pom_licenses(root),
        )

    def test_reads_parent_coordinate_for_inherited_license(self) -> None:
        root = ET.fromstring(
            """<project xmlns="http://maven.apache.org/POM/4.0.0"><parent>
            <groupId>example</groupId><artifactId>parent</artifactId><version>1.2.3</version>
            </parent></project>"""
        )
        self.assertEqual(("example", "parent", "1.2.3"), parent_coordinate(root))


if __name__ == "__main__":
    unittest.main()
