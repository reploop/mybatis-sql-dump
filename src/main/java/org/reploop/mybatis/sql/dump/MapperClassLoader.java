package org.reploop.mybatis.sql.dump;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

public class MapperClassLoader extends ClassLoader {
    private final Set<String> cps;

    public MapperClassLoader(ClassLoader parent, Set<String> cps) {
        super(parent);
        this.cps = cps;
    }

    public MapperClassLoader(Set<String> cps) {
        this.cps = cps;
    }

    private Path toClassFile(String name) {
        return Paths.get(name.replace('.', '/') + ".class");
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        URL url = findResource(name);
        if (null != url) {
            try {
                byte[] b = Files.readAllBytes(Paths.get(url.toURI()));
                return defineClass(name, b, 0, b.length);
            } catch (IOException | URISyntaxException ignored) {
            }
        }
        return super.findClass(name);
    }

    @Override
    protected URL findResource(String name) {
        Path filename = toClassFile(name);
        for (String cp : cps) {
            if (cp.endsWith("classes")) {
                Path path = Paths.get(cp).resolve(filename).normalize();
                if (Files.exists(path)) {
                    try {
                        return path.toUri().toURL();
                    } catch (MalformedURLException ignored) {
                    }
                }
            }
        }
        return super.findResource(name);
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        URL url = findResource(name);
        if (null != url) {
            return Collections.enumeration(Collections.singleton(url));
        } else {
            return Collections.enumeration(Collections.emptyList());
        }
    }
}
