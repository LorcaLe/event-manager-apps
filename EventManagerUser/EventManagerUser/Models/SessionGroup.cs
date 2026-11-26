namespace EventManagerUser.Models;

/// <summary>Sessions of one day, with a heading such as "Day 2 · 11/11/2026".</summary>
public class SessionGroup : List<SessionItem>
{
    public string Title { get; }

    public SessionGroup(string title, IEnumerable<SessionItem> sessions) : base(sessions)
    {
        Title = title;
    }
}
