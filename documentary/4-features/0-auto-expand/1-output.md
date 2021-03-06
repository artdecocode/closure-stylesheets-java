### Output Prefixes

To generate a separate CSS file with expanded prefixes, the `--output-browser-prefix` arg must be set to the desired location of the file. In this case, an additional JSON file with the map of properties and values that were expanded will be written to the same location but under the `.json` name. It then can be used in conjunction with the `CSS.supports` function to test whether the prefixes file is required.

<java jar="closure-stylesheets.jar" lang="css" console="closure-stylesheets">
  --expand-browser-prefix --output-browser-prefix example/prefixes.css --pretty-print example/prefix.css
</java>

As you can see, the input does not contain the expanded properties, however 2 new files were generated:

<table>
<tr><th>example/prefixes.css</th><th>example/prefixes.css.json</th></tr>
<!-- block-start -->
<tr><td>

%EXAMPLE: example/prefixes.css%
</td>
<td>

%EXAMPLE: example/prefixes.css.json%
</td></tr>
</table>

There are 3 general cases for the map:

1. A property regardless of the value, such as `flex-flow` that will be expanded into `-[]-flex-flow`. The shortest value will be added to the map (_`row wrap`_), because here we expand the property name.
1. A value of a property, such as `display: flex` or `display: inline-flex`. Each value will be added to the map to be tested against its property name.
1. A value which is a function, like `calc` or `linear-gradient`. The shorted value will be selected (e.g., between `calc(10px)` and `calc(100vh - 10rem)`, the first one will be selected) and its property name used (such as `width` in the example).

The map will only be created for unprefixed properties, because only they are expanded, i.e. `-ms-flex` is not expanded and will not be tested against because it's already in the CSS.

Now the prefixes map can be used on a page to figure out if to download the fallbacks:

%EXAMPLE: example/script%

And a noscript version should be added to the head of the document:

```html
<html>
  <head>
    <!-- the support detection script -->
    <noscript>
      <link href="prefixes.css" rel="stylesheet">
    </noscript>
  </head>
</html>
```


%~%