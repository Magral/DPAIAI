#!/usr/bin/env python3
import json,unittest
from refresh_catalog import extract,normalize,safe,root,raw_url

class RefreshTest(unittest.TestCase):
    def test_current_catalog_obeys_apk_resource_limits(self):
        for entry in json.loads((root/'tv/strategies/catalog.json').read_text())['strategies']:
            self.assertTrue(safe(entry['args']),entry['id'])
    def test_text_and_html_commands_without_executing_script(self):
        self.assertIn('-s1 -d2',extract('<html><pre>-s1 -d2</pre><script>"-f1"</script></html>'))
        self.assertNotIn('-f1',extract('<html><script>"-f1"</script></html>'))
        self.assertIn('-s1 -r1+s',extract('listOf("-s1 -r1+s", "-d2")'))
        with self.assertRaises(ValueError):normalize('--split 1; curl https://example.com')
        with self.assertRaises(ValueError):normalize('--ip 0.0.0.0 --port 1080')
        with self.assertRaises(ValueError):normalize('--fake-data /etc/passwd')
    def test_resource_limits(self):
        self.assertFalse(safe('--udp-fake 9999'))
        self.assertFalse(safe('--timeout 120'))
        self.assertFalse(safe('--auto r '*17))
        self.assertTrue(safe('--split 1 --timeout 8'))
    def test_fixed_github_source_translates_to_raw(self):
        self.assertEqual('https://raw.githubusercontent.com/owner/repo/main/file',raw_url('https://github.com/owner/repo/blob/main/file'))

if __name__=='__main__':unittest.main()
