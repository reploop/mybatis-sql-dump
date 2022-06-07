<table border="0" cellborder="1" cellspacing="0" cellpadding="1">
    <tr>
        <td><b>${name}</b></td>
    </tr>
    <tr>
        <td>
            <#if columns?size != 0 >
                <table border="0" cellborder="0" cellspacing="0">
                    <#list columns as column>
                        <tr>
                            <td align="left" port="${column}">${column}</td>
                        </tr>
                    </#list>
                </table>
            </#if>
        </td>
    </tr>
</table>