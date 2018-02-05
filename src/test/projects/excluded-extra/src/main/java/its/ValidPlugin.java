package its;

import com.facebook.presto.spi.Plugin;

import java.util.List;

import static java.util.Collections.emptyList;

public class ValidPlugin
        implements Plugin
{
    @Override
    public <T> List<T> getServices(Class<T> type)
    {
        return emptyList();
    }
}
