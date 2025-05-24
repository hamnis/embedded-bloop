# embedded-bloop

Starts up a bloop server and can generate a bsp connection file.

This is a scala version of the [gist](https://gist.github.com/hamnis/64f16a34c29975586d9416723ddeeca7)

This will create graal native images, so we should be able to make bloop easier to use.

## Installation

### Download using mise
Get mise [here](https://mise.jdx.dev/).

```
mise use "ubi:hamnis/embedded-bloop@latest"
```

### Manual installation via download of files from github releases.

If you download the file on macos using a browser, make sure you allow it to run by running

`xattr -d com.apple.quarantine /path/to/downloaded-file`


You also have to make sure you make this executable

`chmod 755 /path/to/downloaded-file`
