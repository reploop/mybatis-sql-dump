<table border="0" cellborder="1" cellspacing="0" cellpadding="1">
    <tr>
        <td><b>${name}</b></td>
    </tr>
    <tr>
        <td>
            <table border="0" cellborder="0" cellspacing="0">
                <#list columns as column>
                    <tr>
                        <td align="left" port="${column}">${column}</td>
                    </tr>
                </#list>
            </table>
        </td>
    </tr>
</table>